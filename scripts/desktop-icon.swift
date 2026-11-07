// Renders hopi-desktop/icon/Hopi.svg into Hopi.icns (the .dmg's icon) and the window's
// hopi-icon.png. macOS only: AppKit draws the SVG, iconutil packs the set.
// Run from the repository root: swift scripts/desktop-icon.swift
import AppKit

let icon = URL(fileURLWithPath: "hopi-desktop/icon")
guard let svg = NSImage(contentsOf: icon.appendingPathComponent("Hopi.svg")) else {
    fatalError("cannot read hopi-desktop/icon/Hopi.svg")
}

func png(_ size: Int, _ url: URL) {
    let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8,
        samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
        bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    NSGraphicsContext.current?.imageInterpolation = .high
    svg.draw(in: NSRect(x: 0, y: 0, width: size, height: size))
    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: url)
}

let set = FileManager.default.temporaryDirectory.appendingPathComponent("Hopi.iconset")
try? FileManager.default.removeItem(at: set)
try! FileManager.default.createDirectory(at: set, withIntermediateDirectories: true)
for base in [16, 32, 128, 256, 512] {
    png(base, set.appendingPathComponent("icon_\(base)x\(base).png"))
    png(base * 2, set.appendingPathComponent("icon_\(base)x\(base)@2x.png"))
}
let iconutil = Process()
iconutil.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
iconutil.arguments = ["-c", "icns", set.path, "-o", icon.appendingPathComponent("Hopi.icns").path]
try! iconutil.run()
iconutil.waitUntilExit()
precondition(iconutil.terminationStatus == 0, "iconutil failed")
png(512, URL(fileURLWithPath: "hopi-desktop/src/main/resources/hopi-icon.png"))
print("hopi-desktop/icon/Hopi.icns, hopi-desktop/src/main/resources/hopi-icon.png")
