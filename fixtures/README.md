# Synthetic fixtures

Run `scripts/make-fixtures.sh` from the project root with FFmpeg including the libx264 and AAC encoders. It generates a 30-second moving test pattern with a 440 Hz tone, an extended M3U and XMLTV guide relative to current UTC. All content is synthetic. Generated files are ignored by version control.

Serve the generated directory locally as the script describes. Android Emulator's `10.0.2.2` reaches the host loopback. Enable HTTP sources **explicitly on the test installation** and import the printed M3U address. For a physical TV, bind the server to the LAN address, replace the host in both M3U addresses/guide header, and use only a private trusted test network. Stop the server afterward.

The same HLS is listed once as live and once as an explicit movie to test both navigation and resume. It is a finite test file; its live entry will end after 30 seconds, which is intentional. It does not simulate a persistent live provider or catch-up service.

No generated fixture or provider URL is preloaded into Astra TV. Unit tests also contain malformed M3U, duplicate guide IDs, quoted commas, relative paths, XML offsets/DTD, provider JSON, encrypted-placeholder Room records, and restricted cached episodes.
