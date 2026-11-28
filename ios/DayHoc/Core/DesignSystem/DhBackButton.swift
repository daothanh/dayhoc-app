import SwiftUI

/// Nút lùi của App Bar Detail (Figma `Navigation/App Bar`): icon Solar `ic_arrow_back` trong `DhIconButton`.
struct DhBackButton: View {
    let action: () -> Void

    var body: some View { DhIconButton(icon: "ic_arrow_back", label: "back", action: action) }
}
