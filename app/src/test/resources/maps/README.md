# Native map preview fixtures

© OpenStreetMap contributors. https://www.openstreetmap.org/copyright

Thirteen existing raster tiles from https://tile.openstreetmap.org/ were retrieved for the visible Tokyo-region phone and wide-screen map viewports on 2026-10-13. They are used only by native UI rendering tests, with network requests disabled. They are not included in the Android app.

The fixtures verify geography, map projection and circle presentation. They do not verify map networking or device location updates. Regional settings still use the user's selected latitude, longitude and radius at runtime.
