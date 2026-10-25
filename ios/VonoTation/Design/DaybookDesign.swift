import SwiftUI
import UIKit

/// Sand & Cocoa for Voitation; Vocading retains the original Daybook palette.
enum Daybook {
    private static func palette(_ light: UInt32,_ dark: UInt32,_ legacyLight: UInt32,_ legacyDark: UInt32) -> Color {
        AppProduct.current == .notes ? adaptive(legacyLight, legacyDark) : adaptive(light, dark)
    }
    static let paper = palette(0xeae3d9, 0x211b17, 0xf3f1ea, 0x121c19)
    static let surface = palette(0xfff9ef, 0x30261f, 0xfffcf6, 0x1b2923)
    static let ink = palette(0x372d25, 0xfbf3e8, 0x25352f, 0xeef3e9)
    static let muted = palette(0x6b5b4a, 0xd1bea7, 0x596960, 0xb2c2b5)
    static let accent = palette(0x6b4f3c, 0xe5c8a1, 0x3f6b5f, 0xaccebb)
    static let soft = palette(0xe9ded0, 0x45372b, 0xe3e9df, 0x2a3d32)
    static let line = palette(0x9a8468, 0xad9170, 0xcbd5ca, 0x3f5748)
    static let fieldOutline = palette(0x9a8468, 0xad9170, 0x7b8e80, 0x6e8c77)
    static let action = palette(0x6b4f3c, 0xe5c8a1, 0x28594e, 0xaccebb)
    static let actionInk = palette(0xffffff, 0x211b17, 0xffffff, 0x15271e)
    static let brand = palette(0x6b4f3c, 0xe5c8a1, 0xa46144, 0xd9ab8d)
    static let coral = adaptive(0xae403d, 0xffb4aa)
    static let shadow = palette(0x372d25, 0x211b17, 0x25352f, 0x07110c)
    static let underlayNear = AppProduct.current == .notes ? surface.opacity(0.75) : adaptive(0xe5d4bb, 0x4a392c)
    static let underlayMiddle = AppProduct.current == .notes ? soft : adaptive(0xc7b291, 0x634d39)

    private static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((value >> 16) & 255) / 255,
                           green: CGFloat((value >> 8) & 255) / 255,
                           blue: CGFloat(value & 255) / 255, alpha: 1)
        })
    }

    @MainActor static func feedback(_ enabled: Bool, strong: Bool = false) {
        guard enabled else { return }
        UIImpactFeedbackGenerator(style: strong ? .medium : .soft).impactOccurred()
    }
}

struct LinenBackground: View {
    var body: some View {
        Daybook.paper.overlay {
            Canvas { context, size in
                var fibers = Path()
                for y in stride(from: CGFloat(0), through: size.height, by: 3) {
                    fibers.move(to: CGPoint(x: 0, y: y))
                    fibers.addLine(to: CGPoint(x: size.width, y: y))
                }
                for x in stride(from: CGFloat(0), through: size.width, by: 4) {
                    fibers.move(to: CGPoint(x: x, y: 0))
                    fibers.addLine(to: CGPoint(x: x, y: size.height))
                }
                context.stroke(fibers, with: .color(Daybook.accent.opacity(0.018)), lineWidth: 0.5)
            }
            .accessibilityHidden(true)
            .allowsHitTesting(false)
        }
        .ignoresSafeArea()
    }
}

struct DaybookButtonStyle: ButtonStyle {
    var primary = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.medium))
            .foregroundStyle(primary ? Daybook.actionInk : configuration.role == .destructive ? Daybook.coral : Daybook.accent)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(minHeight: 44)
            .background(primary ? Daybook.action : Daybook.surface, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Daybook.line.opacity(primary ? 0 : 0.85), lineWidth: 0.5))
            .opacity(configuration.isPressed ? 0.8 : 1)
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.98 : 1)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

struct DaybookCard<Content: View>: View {
    private let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) { content }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Daybook.surface, in: RoundedRectangle(cornerRadius: 17))
            .overlay(RoundedRectangle(cornerRadius: 17).stroke(Daybook.line.opacity(0.75), lineWidth: 0.5))
    }
}

struct DaybookMark: View {
    var size: CGFloat = 30
    var color: Color = Daybook.brand
    var body: some View {
        if AppProduct.current == .notes { VocadingSymbol("mark", size: size) }
        else { Canvas { context, size in
            let scale = min(size.width / 30, size.height / 31)
            context.scaleBy(x: scale, y: scale)
            context.translateBy(x: 15, y: 15)
            context.rotate(by: .degrees(13))
            context.translateBy(x: -15, y: -15)
            for rect in [CGRect(x: 2, y: 10, width: 4, height: 11),
                         CGRect(x: 9, y: 4, width: 4, height: 23),
                         CGRect(x: 16, y: 6, width: 4, height: 18),
                         CGRect(x: 23, y: 11, width: 4, height: 8)] {
                context.fill(Path(roundedRect: rect, cornerRadius: 2), with: .color(color))
            }
            context.translateBy(x: 19, y: 28)
            context.rotate(by: .degrees(-45))
            context.fill(Path(roundedRect: CGRect(x: -4, y: -2, width: 9, height: 4), cornerRadius: 2), with: .color(color))
        }
        .frame(width: size, height: size * 31 / 30)
        .accessibilityHidden(true)
        }
    }
}

struct ThoughtMark: Shape {
    func path(in rect: CGRect) -> Path {
        let scale = min(rect.width, rect.height) / 24
        let x = (rect.width - scale * 24) / 2
        let y = (rect.height - scale * 24) / 2
        return Path { path in
            path.move(to: CGPoint(x: x + 5 * scale, y: y + 3 * scale))
            path.addLine(to: CGPoint(x: x + 15 * scale, y: y + 3 * scale))
            path.addLine(to: CGPoint(x: x + 20 * scale, y: y + 8 * scale))
            path.addLine(to: CGPoint(x: x + 20 * scale, y: y + 21 * scale))
            path.addLine(to: CGPoint(x: x + 5 * scale, y: y + 21 * scale))
            path.closeSubpath()
            path.move(to: CGPoint(x: x + 15 * scale, y: y + 3 * scale))
            path.addLine(to: CGPoint(x: x + 15 * scale, y: y + 8 * scale))
            path.addLine(to: CGPoint(x: x + 20 * scale, y: y + 8 * scale))
            for line in [12, 16] {
                path.move(to: CGPoint(x: x + 9 * scale, y: y + CGFloat(line) * scale))
                path.addLine(to: CGPoint(x: x + 16 * scale, y: y + CGFloat(line) * scale))
            }
        }
    }
}

struct EmptyDaybook: View {
    let title: String
    let detail: String
    let symbol: String
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Image(systemName: symbol).font(.title2).foregroundStyle(Daybook.accent)
            Text(title).font(.headline).foregroundStyle(Daybook.ink)
            Text(detail).font(.subheadline).foregroundStyle(Daybook.muted).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 20)
        .accessibilityElement(children: .combine)
    }
}

struct DaybookSectionTitle: View {
    let title: String
    var body: some View {
        Text(title.uppercased()).font(.caption.weight(.semibold)).tracking(0.6).foregroundStyle(Daybook.accent)
            .padding(.top, 8)
            .accessibilityAddTraits(.isHeader)
    }
}


struct VocadingSymbol: View {
    let name: String
    var size: CGFloat = 22
    init(_ name: String, size: CGFloat = 22) { self.name = name; self.size = size }
    private var isFeature: Bool { ["voice", "call", "import", "transcript", "share", "delete", "stop", "settings"].contains(name) }
    var body: some View {
        Image("Vocading-" + name).resizable().renderingMode(isFeature ? .template : .original)
            .scaledToFit().frame(width: size, height: size).accessibilityHidden(true)
    }
}
