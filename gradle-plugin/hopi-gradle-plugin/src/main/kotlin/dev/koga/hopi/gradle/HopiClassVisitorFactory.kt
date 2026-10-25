package dev.koga.hopi.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import dev.koga.hopi.instrument.HopiRewriter
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor

/** AGP's entry into [HopiRewriter], registered for debuggable variants only. */
public abstract class HopiClassVisitorFactory : AsmClassVisitorFactory<HopiClassVisitorFactory.Parameters> {
    public interface Parameters : InstrumentationParameters {
        @get:Input
        public val okhttp: Property<Boolean>

        @get:Input
        public val urlConnection: Property<Boolean>
    }

    private val options: HopiRewriter.Options
        get() = HopiRewriter.Options(okhttp = parameters.get().okhttp.get(), urlConnection = parameters.get().urlConnection.get())

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        HopiRewriter.visitor(instrumentationContext.apiVersion.get(), nextClassVisitor, classContext.currentClassData.className, options)

    override fun isInstrumentable(classData: ClassData): Boolean = HopiRewriter.isInstrumentable(classData.className, options)
}
