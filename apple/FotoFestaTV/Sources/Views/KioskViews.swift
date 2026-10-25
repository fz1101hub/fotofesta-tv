import FotoFestaCore
import SwiftUI

/// Tastierino del chiosco: dieci cifre da scegliere con il telecomando o il mouse (sul Mac anche
/// dalla tastiera). Con la quarta cifra il PIN si controlla da solo.
struct PinPadView: View {
    let purpose: PinPurpose
    let onSubmit: (String) -> Bool
    let onCancel: () -> Void
    @State private var digits = ""
    @State private var wrong = false

    var body: some View {
        VStack(spacing: 36) {
            VStack(spacing: 10) {
                Text(L("pin.kicker").uppercased()).font(.caption.bold()).foregroundStyle(Theme.secondaryText)
                Text(purpose.title).font(.title2.bold()).multilineTextAlignment(.center)
            }
            HStack(spacing: 22) {
                ForEach(0..<KioskLock.pinLength, id: \.self) { index in
                    Circle()
                        .fill(index < digits.count ? Color.primary : Color.clear)
                        .overlay(Circle().strokeBorder(Color.primary, lineWidth: 3))
                        .frame(width: 30, height: 30)
                }
            }
            .accessibilityLabel("\(digits.count)/\(KioskLock.pinLength)")
            Text(L("pin.wrong")).font(.headline).foregroundStyle(.red).opacity(wrong ? 1 : 0)
            Grid(horizontalSpacing: 20, verticalSpacing: 20) {
                ForEach([["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"]], id: \.self) { row in
                    GridRow {
                        ForEach(row, id: \.self) { digit in key(digit) }
                    }
                }
                GridRow {
                    Button(L("pin.cancel"), action: onCancel).frame(minWidth: 140)
                    key("0")
                    Button {
                        if !digits.isEmpty { digits.removeLast() }
                    } label: {
                        Image(systemName: "delete.left").frame(minWidth: 100, minHeight: 44)
                    }
                }
            }
        }
        .padding(60)
        #if os(macOS)
        .frame(minWidth: 460)
        .focusable()
        .focusEffectDisabled()
        .onKeyPress(characters: .decimalDigits) { press in
            press.characters.forEach { type(String($0)) }
            return .handled
        }
        .onKeyPress(.delete) {
            if !digits.isEmpty { digits.removeLast() }
            return .handled
        }
        .onKeyPress(.escape) {
            onCancel()
            return .handled
        }
        #endif
    }

    private func key(_ digit: String) -> some View {
        Button { type(digit) } label: {
            Text(digit).font(.title.monospacedDigit().bold()).frame(minWidth: 100, minHeight: 44)
        }
    }

    private func type(_ digit: String) {
        guard digits.count < KioskLock.pinLength else { return }
        wrong = false
        digits += digit
        guard digits.count == KioskLock.pinLength else { return }
        if !onSubmit(digits) {
            wrong = true
            digits = ""
        }
    }
}

/// Schermata protetta dal chiosco: senza un PIN giusto negli ultimi minuti si apre il tastierino.
/// Il controllo si fa una volta all'apertura, così la schermata non si richiude mentre la si usa.
struct KioskGate<Content: View>: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let purpose: PinPurpose
    @ViewBuilder let content: () -> Content
    @State private var checked = false
    @State private var open = false

    var body: some View {
        Group {
            if open {
                content()
            } else if checked {
                PinPadView(purpose: purpose) { pin in
                    let right = model.submitPin(pin, for: purpose)
                    if right { open = true }
                    return right
                } onCancel: {
                    dismiss()
                }
            } else {
                Color.clear
            }
        }
        .onAppear {
            guard !checked else { return }
            checked = true
            open = !model.kioskLocked
        }
    }
}
