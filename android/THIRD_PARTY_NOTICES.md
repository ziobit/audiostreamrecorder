# Android third-party components

Stream Saver author: **Marco Vezzale <marcovezzale@gmail.com>**.

## Jump3r 1.0.5

The app uses `de.sciss:jump3r:1.0.5`, a Java port of LAME under LGPL 2.1 or later.
LAME, Ken Händel, Vadym Vikulin, Hanns Holger Rutz, and individual contributors
retain their original authorship and source notices.

The complete notice and license are in
[app/src/main/assets/third_party/NOTICE.txt](app/src/main/assets/third_party/NOTICE.txt),
accessible through **About & licenses → Encoder licenses**.

Gradle bundles the exact unmodified source archive into
`assets/third_party/jump3r-1.0.5-sources.jar` in the APK. Extract it with a ZIP tool.
The application source and build scripts are available here, allowing the encoder
to be replaced with a compatible modified version and the application rebuilt.

| Artifact | SHA-256 |
| --- | --- |
| jump3r-1.0.5.jar | `89a5894eee514f9e86a61f01d59b2665af6eddcc25b01accc86514bae16f0054` |
| jump3r-1.0.5-sources.jar | `bbd353fcb01d6192b7faf00939cbb4f65e31c6911de4972007af0dd9775614e9` |

Source: https://repo.maven.apache.org/maven2/de/sciss/jump3r/1.0.5/  
Project: https://git.iem.at/sciss/jump3r  
LAME: https://lame.sourceforge.io/

Stream Saver's integration adapter is Apache-2.0 application code and calls the
encoding core directly. It does not use the desktop Java Sound wrapper.
