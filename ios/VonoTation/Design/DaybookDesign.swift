import SwiftUI
import UIKit

/// The same quiet paper and botanical palette used by the Android Daybook.
enum Daybook {
    static let paper = adaptive(0xf3f1ea, 0x121c19)
    static let surface = adaptive(0xfffcf6, 0x1b2923)
    static let ink = adaptive(0x25352f, 0xeef3e9)
    static let muted = adaptive(0x596960, 0xb2c2b5)
    static let accent = adaptive(0x3f6b5f, 0xaccebb)
    static let soft = adaptive(0xe3e9df, 0x2a3d32)
    static let line = adaptive(0xcbd5ca, 0x3f5748)
    static let fieldOutline = adaptive(0x7b8e80, 0x6e8c77)
    static let coral = adaptive(0xae403d, 0xffb4aa)
    static let action = adaptive(0x28594e, 0xaccebb)
    static let actionInk = adaptive(0xffffff, 0x15271e)
    static let brand = adaptive(0xa46144, 0xd9ab8d)
    static let shadow = adaptive(0x25352f, 0x07110c)

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
        Canvas { context, size in
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
