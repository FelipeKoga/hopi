package dev.koga.fakesdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import javax.net.ssl.HttpsURLConnection;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.CertificatePinner;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * A third-party SDK's networking, in every shape automatic capture must cover: a static
 * OkHttpClient with its own application and network interceptors (enqueue and execute), a subclass
 * built by reflection, a derived newBuilder() client, and HttpURLConnection GET, fixed-length POST,
 * a 404 read through getErrorStream, URL.openStream() and a URL::openConnection method reference.
 * It knows nothing of Hopi. {@link #run} makes nine requests to {@code base} on the calling
 * thread (never the main thread) and returns one line per request.
 */
public final class FakeSdk {
    private FakeSdk() {}

    private static final MediaType JSON = MediaType.get("application/json");

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder().header("X-Fake-Sdk", "1").build()))
            .addNetworkInterceptor(chain -> chain.proceed(chain.request()))
            .build();

    /** An SDK's own OkHttpClient subclass, built by reflection as some SDKs do. */
    public static class SdkClient extends OkHttpClient {
        public SdkClient() {
            super();
        }
    }

    /** A connection opener as a method reference (rewrite 7 of the design). */
    interface Opener {
        URLConnection open(URL url) throws IOException;
    }

    public static List<String> run(String base) throws Exception {
        List<String> lines = new ArrayList<>();
        // 1. enqueue of a JSON POST on OkHttp's dispatcher.
        CountDownLatch done = new CountDownLatch(1);
        String[] enqueued = new String[1];
        CLIENT.newCall(new Request.Builder().url(base + "/echo?sdk=enqueue").post(RequestBody.create("{\"event\":\"app_open\"}", JSON)).build())
                .enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException e) {
                        enqueued[0] = "enqueue failed: " + e;
                        done.countDown();
                    }

                    @Override public void onResponse(Call call, Response response) throws IOException {
                        try (Response r = response) {
                            enqueued[0] = "okhttp enqueue POST " + r.code() + " " + r.body().string().length() + " bytes";
                        }
                        done.countDown();
                    }
                });
        if (!done.await(30, TimeUnit.SECONDS)) enqueued[0] = "enqueue timed out";
        lines.add(enqueued[0]);
        // 2. execute of a GET.
        try (Response r = CLIENT.newCall(new Request.Builder().url(base + "/json?sdk=execute").build()).execute()) {
            lines.add("okhttp execute GET " + r.code() + " " + r.body().string().length() + " bytes");
        }
        // 3. a subclass built by reflection.
        OkHttpClient reflected = SdkClient.class.getDeclaredConstructor().newInstance();
        try (Response r = reflected.newCall(new Request.Builder().url(base + "/json?sdk=subclass").build()).execute()) {
            lines.add("okhttp subclass GET " + r.code());
        }
        // 4. a derived client.
        OkHttpClient derived = CLIENT.newBuilder().addInterceptor(chain -> chain.proceed(chain.request().newBuilder().header("X-Derived", "1").build())).build();
        try (Response r = derived.newCall(new Request.Builder().url(base + "/json?sdk=derived").build()).execute()) {
            lines.add("okhttp derived GET " + r.code());
        }
        // 5. HttpURLConnection GET.
        HttpURLConnection get = (HttpURLConnection) new URL(base + "/json?sdk=urlconnection").openConnection();
        get.setRequestProperty("X-Fake-Sdk", "1");
        lines.add("urlconnection GET " + get.getResponseCode() + " " + readAll(get.getInputStream()).length + " bytes");
        get.disconnect();
        // 6. HttpURLConnection POST, fixed-length streaming.
        HttpURLConnection post = (HttpURLConnection) new URL(base + "/echo?sdk=urlconnection-post").openConnection();
        byte[] payload = "{\"event\":\"purchase\",\"value\":42}".getBytes(StandardCharsets.UTF_8);
        post.setDoOutput(true);
        post.setRequestProperty("Content-Type", "application/json");
        post.setFixedLengthStreamingMode(payload.length);
        try (OutputStream out = post.getOutputStream()) {
            out.write(payload);
        }
        lines.add("urlconnection POST " + post.getResponseCode() + " " + readAll(post.getInputStream()).length + " bytes");
        post.disconnect();
        // 7. A 404 read through getErrorStream.
        HttpURLConnection missing = (HttpURLConnection) new URL(base + "/missing?sdk=urlconnection-404").openConnection();
        int code = missing.getResponseCode();
        InputStream error = missing.getErrorStream();
        lines.add("urlconnection 404 " + code + " " + (error == null ? 0 : readAll(error).length) + " bytes");
        missing.disconnect();
        // 8. URL.openStream(), then a method reference.
        try (InputStream in = new URL(base + "/json?sdk=openStream").openStream()) {
            lines.add("url openStream " + readAll(in).length + " bytes");
        }
        Opener opener = URL::openConnection;
        HttpURLConnection referenced = (HttpURLConnection) opener.open(new URL(base + "/json?sdk=methodref"));
        lines.add("url method reference " + referenced.getResponseCode() + " " + readAll(referenced.getInputStream()).length + " bytes");
        referenced.disconnect();
        return lines;
    }

    /** One request of {@link #verify}: returns the status and reads the body into the digest. */
    private interface Scenario {
        int run(String tag, Digest body) throws Exception;
    }

    /** The bytes the app read: counted and hashed as they stream, never held whole. */
    private static final class Digest {
        final MessageDigest sha;
        long bytes;
        String detail;

        Digest() throws NoSuchAlgorithmException {
            sha = MessageDigest.getInstance("SHA-256");
        }

        void readAll(InputStream in) throws IOException {
            try (InputStream stream = in) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = stream.read(buffer)) != -1) {
                    sha.update(buffer, 0, n);
                    bytes += n;
                }
            }
        }

        String hex() {
            StringBuilder out = new StringBuilder();
            for (byte b : sha.digest()) out.append(String.format("%02x", b));
            return out.toString();
        }
    }

    private static final byte[] PATTERN_1MB = new byte[1024 * 1024];

    static {
        for (int i = 0; i < PATTERN_1MB.length; i++) PATTERN_1MB[i] = (byte) (i % 251);
    }

    /**
     * The end-to-end capture check's OkHttp and HttpURLConnection half (benchmark/android-capture/e2e):
     * one request of each shape through this SDK's own client and through {@code URL.openConnection()},
     * each tagged {@code X-Hopi-Verify: <name>} so the host can match records to requests, returning one
     * {@code {"verify":…}} line per request with the status (or the error's class), the bytes read and
     * their SHA-256, so a capture build and one with no runtime can be compared byte for byte.
     * {@code https} is a public HTTPS URL. Runs on the calling thread (never the main thread).
     */
    public static List<String> verify(String base, String https) throws Exception {
        List<String> lines = new ArrayList<>();
        // OkHttp, this SDK's static client (its own interceptors add X-Fake-Sdk).
        okhttp(lines, "ok-get-json", base + "/json", null, null);
        okhttp(lines, "ok-post-echo", base + "/echo", RequestBody.create("{\"event\":\"verify\",\"n\":1}", JSON), "Bearer sample-secret-6f1d2c");
        okhttp(lines, "ok-404", base + "/missing", null, null);
        okhttp(lines, "ok-refused", "http://127.0.0.1:9/", null, null);
        okhttp(lines, "ok-unresolvable", "https://hopi-sample.invalid/", null, null);
        okhttp(lines, "ok-large", base + "/large", null, null);
        okhttp(lines, "ok-chunked", base + "/chunked", null, null);
        okhttp(lines, "ok-redirect", base + "/redirect?to=/json", null, null);
        okhttp(lines, "ok-giant-20", base + "/giant?mb=20", null, null);
        okhttp(lines, "ok-giant-20-chunked", base + "/giant?mb=20&chunked=1", null, null);
        okhttp(lines, "ok-gzip", base + "/gzip?kb=512", null, null);
        lines.add(line("ok-gzip-raw", "okhttp", (tag, body) -> {
            // The app asks for gzip itself, so OkHttp hands it the compressed bytes.
            Request request = new Request.Builder().url(base + "/gzip?kb=64").header("X-Hopi-Verify", tag).header("Accept-Encoding", "gzip").build();
            try (Response r = CLIENT.newCall(request).execute()) {
                body.readAll(r.body().byteStream());
                return r.code();
            }
        }));
        okhttp(lines, "ok-drop", base + "/drop?kb=64", null, null);
        okhttp(lines, "ok-post-1mb", base + "/sink", RequestBody.create(PATTERN_1MB, MediaType.get("application/octet-stream")), null);
        lines.add(line("ok-https", "okhttp", (tag, body) -> {
            try (Response r = CLIENT.newCall(new Request.Builder().url(https).header("X-Hopi-Verify", tag).build()).execute()) {
                body.readAll(r.body().byteStream());
                body.detail = r.protocol().toString();
                return r.code();
            }
        }));
        // HttpURLConnection.
        connection(lines, "uc-get-json", base + "/json", null, null, false);
        connection(lines, "uc-post-echo", base + "/echo", "{\"event\":\"verify\",\"n\":2}".getBytes(StandardCharsets.UTF_8), "Bearer sample-secret-6f1d2c", false);
        connection(lines, "uc-404", base + "/missing", null, null, false);
        connection(lines, "uc-refused", "http://127.0.0.1:9/", null, null, false);
        connection(lines, "uc-unresolvable", "https://hopi-sample.invalid/", null, null, false);
        connection(lines, "uc-large", base + "/large", null, null, false);
        connection(lines, "uc-chunked", base + "/chunked", null, null, false);
        connection(lines, "uc-redirect", base + "/redirect?to=/json", null, null, false);
        connection(lines, "uc-giant-20", base + "/giant?mb=20", null, null, false);
        connection(lines, "uc-giant-20-chunked", base + "/giant?mb=20&chunked=1", null, null, false);
        connection(lines, "uc-gzip", base + "/gzip?kb=512", null, null, false);
        connection(lines, "uc-gzip-raw", base + "/gzip?kb=64", null, null, true);
        connection(lines, "uc-drop", base + "/drop?kb=64", null, null, false);
        connection(lines, "uc-post-1mb", base + "/sink", PATTERN_1MB, null, false);
        lines.add(line("uc-https", "urlconnection", (tag, body) -> {
            HttpsURLConnection c = (HttpsURLConnection) new URL(https).openConnection();
            c.setRequestProperty("X-Hopi-Verify", tag);
            int code = c.getResponseCode();
            body.detail = c.getCipherSuite() + " " + c.getServerCertificates().length + " certs";
            body.readAll(c.getInputStream());
            c.disconnect();
            return code;
        }));
        return lines;
    }

    private static void okhttp(List<String> lines, String name, String url, RequestBody post, String authorization) {
        lines.add(line(name, "okhttp", (tag, body) -> {
            Request.Builder builder = new Request.Builder().url(url).header("X-Hopi-Verify", tag);
            if (authorization != null) builder.header("Authorization", authorization);
            if (post != null) builder.post(post);
            try (Response r = CLIENT.newCall(builder.build()).execute()) {
                body.readAll(r.body().byteStream());
                return r.code();
            }
        }));
    }

    private static void connection(List<String> lines, String name, String url, byte[] post, String authorization, boolean askGzip) {
        lines.add(line(name, "urlconnection", (tag, body) -> {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("X-Hopi-Verify", tag);
            if (authorization != null) c.setRequestProperty("Authorization", authorization);
            if (askGzip) c.setRequestProperty("Accept-Encoding", "gzip");
            if (post != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                // The 1 MB upload streams in chunks; the small one has a fixed length.
                if (post.length > 65536) c.setChunkedStreamingMode(16384); else c.setFixedLengthStreamingMode(post.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(post);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) body.readAll(in);
            c.disconnect();
            return code;
        }));
    }

    private static String line(String name, String engine, Scenario scenario) {
        long start = System.nanoTime();
        String result;
        try {
            Digest body = new Digest();
            int status = scenario.run(name, body);
            result = "\"status\":" + status + ",\"bytes\":" + body.bytes + ",\"sha256\":\"" + body.hex() + "\""
                    + (body.detail == null ? "" : ",\"detail\":\"" + body.detail + "\"");
        } catch (Exception e) {
            result = "\"error\":\"" + e.getClass().getSimpleName() + "\"";
        }
        return "{\"verify\":\"" + name + "\",\"engine\":\"" + engine + "\"," + result + ",\"ms\":" + (System.nanoTime() - start) / 1_000_000 + "}";
    }

    /**
     * The capture A/B workload: {@code count} sequential GETs of {@code path} through this SDK's
     * OkHttp client ({@code kind} "okhttp", execute on the calling thread) or its HttpURLConnection
     * ("urlconnection"), each body read to the end. Returns each request's wall time in nanoseconds,
     * from the call to the body's end.
     */
    public static long[] bench(String base, String kind, String path, int count) throws IOException {
        return bench(base, kind, path, count, 0);
    }

    /**
     * {@link #bench} with {@code gapMicros} of idle time before each request, outside its timed
     * window, as an app's requests are spaced: a background thread that finishes its work between
     * two requests is then parked when the next one starts, which back-to-back requests hide.
     */
    public static long[] bench(String base, String kind, String path, int count, long gapMicros) throws IOException {
        long[] nanos = new long[count];
        for (int i = 0; i < count; i++) {
            if (gapMicros > 0) LockSupport.parkNanos(gapMicros * 1000);
            long start = System.nanoTime();
            if (kind.equals("okhttp")) {
                try (Response r = CLIENT.newCall(new Request.Builder().url(base + path).build()).execute()) {
                    r.body().source().readByteString();
                }
            } else {
                HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
                readAll(connection.getInputStream());
            }
            nanos[i] = System.nanoTime() - start;
        }
        return nanos;
    }

    /** What one {@link #call} saw: the status, the body as text and one response header, or the error. */
    public static final class Result {
        public final int status;
        public final String body;
        public final String header;
        /** The exception's class and message, or null. */
        public final String error;

        Result(int status, String body, String header, String error) {
            this.status = status;
            this.body = body;
            this.header = header;
            this.error = error;
        }

        static Result failed(Exception e) {
            return new Result(-1, "", null, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    /**
     * One request for the sample's rules scenarios, as this SDK makes requests: {@code engine}
     * "okhttp" (its static client, execute) or "urlconnection" ({@code URL.openConnection()}, a
     * fixed-length body, a 4xx read through {@code getErrorStream}). {@code headers} holds name, value
     * pairs; {@code body} is sent as JSON when not null; {@code readHeader} names the response header
     * to report. Never throws: a failure is the result's {@code error}. Runs on the calling thread.
     */
    public static Result call(String engine, String method, String url, String[] headers, String body, String readHeader) {
        try {
            if (engine.equals("okhttp")) {
                Request.Builder builder = new Request.Builder().url(url);
                for (int i = 0; i + 1 < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
                builder.method(method, body == null ? null : RequestBody.create(body, JSON));
                try (Response r = CLIENT.newCall(builder.build()).execute()) {
                    return new Result(r.code(), r.body().string(), r.header(readHeader), null);
                }
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setRequestMethod(method);
                for (int i = 0; i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
                if (body != null) {
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(bytes);
                    }
                }
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
                return new Result(code, text, c.getHeaderField(readHeader), null);
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return Result.failed(e);
        }
    }

    /**
     * A GET of {@code https} through a client that pins the host to a key no server has, as an SDK
     * that pins its backend does: every real connection fails the pin
     * ({@code SSLPeerUnverifiedException}), whatever a Hopi rule edits, and only a response that
     * never touches the network (a mock) gets through.
     */
    public static Result pinned(String https) {
        try {
            String host = new URL(https).getHost();
            OkHttpClient pinned = CLIENT.newBuilder()
                    .certificatePinner(new CertificatePinner.Builder().add(host, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").build())
                    .build();
            try (Response r = pinned.newCall(new Request.Builder().url(https).build()).execute()) {
                return new Result(r.code(), r.body().string(), r.header("X-Hopi-Edited"), null);
            }
        } catch (Exception e) {
            return Result.failed(e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}
