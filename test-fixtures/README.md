# Local Android smoke fixture

From this directory run `python3 -m http.server 18765 --bind 0.0.0.0 --directory http` on the development machine. In an Android emulator, import `http://10.0.2.2:18765/source.json`, search for any keyword, open “Fixture Book”, select chapters and export TXT/EPUB. The fixture uses the emulator's conventional host alias `10.0.2.2`; use the host machine's reachable address on a physical device.

All content is synthetic and stored locally. The server is only for manual device smoke testing and should be stopped afterwards.
