import FotoFestaCore
import SwiftUI
import UIKit

/// Puzzle con le foto della raccolta: prima si sceglie il livello, poi si scambiano i pezzi finché la
/// foto è di nuovo intera. Menu dal tabellone torna alla scelta del livello.
struct PuzzleGameView: View {
    @Environment(AppModel.self) private var model
    @State private var level: PuzzleLevel?

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            if let level {
                PuzzleBoardView(photos: model.photos, level: level) { self.level = nil }
                    .id(level)
            } else {
                levelPicker
            }
        }
        // Dal tabellone Menu torna ai livelli; dai livelli, come sempre, alla schermata precedente.
        .onExitCommand(perform: exitAction)
        #if DEBUG
        .onAppear {
            if ProcessInfo.processInfo.environment["FOTOFESTA_SCREENSHOT"] == "puzzle_game" { level = .medium }
        }
        #endif
    }

    private var exitAction: (() -> Void)? {
        guard level != nil else { return nil }
        return { level = nil }
    }

    private var levelPicker: some View {
        VStack(alignment: .leading, spacing: 48) {
            ScreenTitle(title: L("puzzle.choose_level"), subtitle: L("puzzle.choose_level_body"))
            HStack(spacing: 44) {
                ForEach(PuzzleLevel.allCases, id: \.self) { level in
                    Button {
                        self.level = level
                    } label: {
                        LevelCardLabel(level: level)
                    }
                    .buttonStyle(.card)
                }
            }
            Spacer(minLength: 0)
        }
    }
}

func puzzleLevelName(_ level: PuzzleLevel) -> String {
    switch level {
    case .easy: return L("puzzle.level_easy")
    case .medium: return L("puzzle.level_medium")
    case .hard: return L("puzzle.level_hard")
    }
}

/// Scheda di un livello: disegno della griglia, nome e numero di pezzi.
private struct LevelCardLabel: View {
    let level: PuzzleLevel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            PuzzleGridShape(level: level)
                .stroke(.white, lineWidth: 4)
                .frame(width: 192, height: 108)
            Spacer(minLength: 0)
            Text(puzzleLevelName(level)).font(.title2.bold())
            Text(LP("puzzle.pieces", level.pieces)).font(.callout).foregroundStyle(Theme.secondaryText)
        }
        .foregroundStyle(.white)
        .padding(36)
        .frame(width: 520, height: 340, alignment: .leading)
        .background(LinearGradient(colors: [Color(white: 0.26), Color(white: 0.12)], startPoint: .topLeading, endPoint: .bottomTrailing))
        .accessibilityElement(children: .combine)
    }
}

/// Contorno con le linee della griglia del livello: si capisce a colpo d'occhio quanti pezzi ha.
private struct PuzzleGridShape: Shape {
    let level: PuzzleLevel

    func path(in rect: CGRect) -> Path {
        var path = Path(roundedRect: rect, cornerRadius: 10)
        for column in 1..<level.columns {
            let x = rect.minX + rect.width * CGFloat(column) / CGFloat(level.columns)
            path.move(to: CGPoint(x: x, y: rect.minY))
            path.addLine(to: CGPoint(x: x, y: rect.maxY))
        }
        for row in 1..<level.rows {
            let y = rect.minY + rect.height * CGFloat(row) / CGFloat(level.rows)
            path.move(to: CGPoint(x: rect.minX, y: y))
            path.addLine(to: CGPoint(x: rect.maxX, y: y))
        }
        return path
    }
}

/// Tabellone: intestazione con mosse e comandi, sotto i pezzi in una griglia 16:9.
private struct PuzzleBoardView: View {
    let photos: [Photo]
    let level: PuzzleLevel
    let onChangeLevel: () -> Void

    @State private var photo: Photo?
    @State private var board: PuzzleBoard
    @State private var selected: Int?
    /// Pezzi ritagliati dalla foto, nell'ordine giusto: l'elemento `p` è il pezzo `p`.
    @State private var pieces: [UIImage] = []
    @State private var full: UIImage?
    @State private var failed = false
    @State private var showPhoto = false
    @State private var round = 0
    @FocusState private var focusedCell: Int?
    @FocusState private var newGameFocused: Bool

    init(photos: [Photo], level: PuzzleLevel, onChangeLevel: @escaping () -> Void) {
        self.photos = photos
        self.level = level
        self.onChangeLevel = onChangeLevel
        _board = State(initialValue: PuzzleBoard.shuffled(level))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 32) {
            HStack(alignment: .center, spacing: 32) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("puzzle.kicker", board.moves)).font(.headline).foregroundStyle(Theme.secondaryText)
                    Text(title).font(.title.bold())
                }
                Spacer(minLength: 0)
                Button(showPhoto ? L("puzzle.hide_photo") : L("puzzle.show_photo")) { showPhoto.toggle() }
                    .disabled(full == nil || board.isSolved)
                Button(L("memory.new_game")) { round += 1 }
                    .focused($newGameFocused)
                Button(L("puzzle.change_level")) { onChangeLevel() }
            }
            GeometryReader { geometry in
                let width = min(geometry.size.width, geometry.size.height * 16 / 9)
                boardContent
                    .frame(width: width, height: width * 9 / 16)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .defaultFocus($focusedCell, 0)
        .task(id: round) { await load() }
        // A foto ricomposta i pezzi non servono più: il fuoco passa a "Nuova partita".
        .onChange(of: board.isSolved) { _, solved in
            if solved { newGameFocused = true }
        }
    }

    private var title: String {
        if board.isSolved { return L("puzzle.complete", board.moves) }
        return selected == nil ? L("puzzle.pick_first") : L("puzzle.pick_second")
    }

    @ViewBuilder
    private var boardContent: some View {
        if failed {
            VStack(spacing: 16) {
                Image(systemName: "exclamationmark.triangle").font(.system(size: 64))
                Text(L("photo.unavailable"))
            }
            .foregroundStyle(Theme.secondaryText)
        } else if pieces.count != level.pieces {
            ProgressView()
        } else {
            // A foto ricomposta i pezzi si uniscono senza spazi.
            let gap: CGFloat = board.isSolved ? 0 : 8
            ZStack {
                VStack(spacing: gap) {
                    ForEach(0..<level.rows, id: \.self) { row in
                        HStack(spacing: gap) {
                            ForEach(0..<level.columns, id: \.self) { column in
                                tile(row * level.columns + column)
                            }
                        }
                    }
                }
                if showPhoto, let full {
                    Image(uiImage: full)
                        .resizable()
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .accessibilityHidden(true)
                }
            }
        }
    }

    private func tile(_ cell: Int) -> some View {
        Button {
            choose(cell)
        } label: {
            // La misura la decide la casella; il pezzo sta sopra, ritagliato.
            Color.clear
                .overlay {
                    Image(uiImage: pieces[board.positions[cell]])
                        .resizable()
                        .scaledToFill()
                }
                .clipped()
                .overlay {
                    if selected == cell {
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .strokeBorder(Theme.highlight, lineWidth: 8)
                    }
                }
        }
        .buttonStyle(.card)
        .focused($focusedCell, equals: cell)
        .accessibilityLabel(cellLabel(cell))
    }

    private func cellLabel(_ cell: Int) -> String {
        if selected == cell { return L("puzzle.cell_selected", cell + 1) }
        if board.isCorrect(cell) { return L("puzzle.cell_correct", cell + 1) }
        return L("puzzle.cell", cell + 1)
    }

    private func choose(_ cell: Int) {
        guard !board.isSolved, pieces.count == level.pieces else { return }
        guard let first = selected else {
            selected = cell
            return
        }
        // La stessa casella annulla la scelta; un'altra scambia i due pezzi.
        if first != cell { board.swap(first, cell) }
        selected = nil
    }

    /// Nuova partita: un'altra foto (non la stessa di prima, se possibile) e pezzi mescolati.
    private func load() async {
        let candidates = photos.filter { $0.id != photo?.id }
        guard let next = (candidates.isEmpty ? photos : candidates).randomElement() else {
            failed = true
            return
        }
        photo = next
        board = PuzzleBoard.shuffled(level)
        selected = nil
        showPhoto = false
        pieces = []
        full = nil
        failed = false
        guard let image = await ImageLoader.shared.image(for: next, maxPixelSize: 1920),
              let sliced = PuzzleSlicer.slice(image, level: level) else {
            failed = true
            return
        }
        pieces = sliced.pieces
        full = sliced.full
    }
}

/// Taglia la foto nei pezzi del livello, dalla parte centrale in 16:9.
enum PuzzleSlicer {
    static func slice(_ image: UIImage, level: PuzzleLevel) -> (pieces: [UIImage], full: UIImage)? {
        guard let cgImage = image.cgImage else { return nil }
        let crop = PuzzleGeometry.crop(width: cgImage.width, height: cgImage.height)
        guard let full = cgImage.cropping(to: crop.cgRect) else { return nil }
        var pieces: [UIImage] = []
        for piece in 0..<level.pieces {
            guard let part = cgImage.cropping(to: PuzzleGeometry.piece(piece, of: crop, level: level).cgRect) else { return nil }
            pieces.append(UIImage(cgImage: part))
        }
        return (pieces, UIImage(cgImage: full))
    }
}

private extension PixelRect {
    var cgRect: CGRect { CGRect(x: x, y: y, width: width, height: height) }
}
