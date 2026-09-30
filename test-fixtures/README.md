# Local Android smoke fixture

From this directory run `python3 -m http.server 18765 --bind 0.0.0.0 --directory http` on the development machine. In an Android emulator, import `http://10.0.2.2:18765/source.json`, search for any keyword, open “Fixture Book”, select chapters and export TXT/EPUB. The fixture uses the emulator's conventional host alias `10.0.2.2`; use the host machine's reachable address on a physical device.

All content is synthetic and stored locally. The server is only for manual device smoke testing and should be stopped afterwards.

For the controlled DOM-host regression, use `catalogUrl=http://10.0.2.2:18765/node-source.json`, `sourceFilter==http://10.0.2.2:18765/node`, and any keyword in `CompatibilityProbeActivity`. It tests `java.getElement(...).first().select(...).attr(...)` and `java.getElements(...)` returning nodes through the search list; expected search count is 2. No chapter or copyrighted content is fetched.

For browser resource capture, use `catalogUrl=http://10.0.2.2:18765/sniff-source.json`, `sourceFilter==http://10.0.2.2:18765/sniff`, any keyword, and `captureFirst=true`. The synthetic player requests `sniff-media.mp3`; expected probe stages are `SEARCH_OK`, `DETAIL_OK`, `CATALOG_OK`, then `CONTENT_OK resources=1`. The `.mp3` file is a marker for resource interception, not playable audio.

For a small comic download, import `http://10.0.2.2:18765/comic-source.json`, search for any keyword, open “Fixture Comic”, then use “资源下载” on its one chapter. `comic-page.jpg` contains a valid PNG intentionally served under a misleading suffix. The resulting ZIP should pass `unzip -t` and contain `0001.png`.

For a basic JavaScript rule on older Android, run `CompatibilityProbeActivity` with `catalogUrl=http://10.0.2.2:18765/js-source.json`, `sourceFilter==http://10.0.2.2:18765/js`, any keyword, and `captureFirst=true`. Its content replacement runs `<js>result</js>`; expected final stage is `CONTENT_OK`.
