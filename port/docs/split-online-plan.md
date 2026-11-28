# Zweiter Spieler: Split Screen und Online (Plan, 26.11.2026)

Stand der Untersuchung: Das Spiel kennt einen Spieler. `pPL` (rund 3.600 Zugriffe) und `Key` (228 Lesezugriffe in 27 Dateien)
sind Globale. Gegner zielen mit `pTarget = pPL`. Die Kamera folgt `pPL` (58 Stellen). Inventar, Leben und Waffe liegen in `pG`.
`cPlayer` ist eine `cEm` (Spielfigur-Klasse), `EmMgr.create(0)` baut je nach `pG->pl_type` Leon (0), Ashley (1) oder die
Extra-Figuren (2–5, `PlInitFunc`). Die Figurenwahl ist darum über `pl_type` möglich.

Vorgehen in kleinen Schritten, nach jedem Schritt Build APK (GitHub Actions, Branch `split-experiment`) und Test:

1. **Zweiter Leon bewegt sich** (Patch 0260): Controller auf Kanal 1 → zweiter Leon, `pPL`/`Key` vertauscht in `re4_pl2_move`.
   Kamera folgt noch Spieler 1; Inventar und Leben gemeinsam. Prüfen: erscheint er, läuft er, stürzt das Spiel ab?
2. Gegner jagen den nächsten Spieler (Ziel-Zuweisung `pTarget` an den Stellen, die `pPL` setzen).
3. Kamera und geteilter Bildschirm (zwei Viewports, zweite Kamera).
4. Eigenes Leben/Inventar je Spieler (Teile von `pG` beim Wechsel tauschen).
5. Online: die Positionen/Eingaben der anderen Spieler über `OnlineNet` schicken; entfernte Spieler als zweiter/dritter Leon.
6. Figurenwahl und Name über der Lebensanzeige.

Grenzen: Zwischensequenzen und Ereignisse gehen von einem Spieler aus. Der Kern im eigenen Prozess (IPC) kennt Kanal 1 noch nicht.
