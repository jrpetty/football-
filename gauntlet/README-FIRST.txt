GAUNTLET - READ ME FIRST
========================

No typing needed. Just clicks.

1. Right-click the Gauntlet zip file, choose "Properties", tick "Unblock" at the bottom, press OK.
   (Windows "Smart App Control" blocks downloaded files otherwise. Don't turn Smart App Control off.)
   Then right-click the zip again, choose "Extract All...", then press "Extract".

2. Open the folder and double-click "start-gauntlet.bat".
   If Windows says "Windows protected your PC", click "More info", then "Run anyway".
   If it asks to install Node.js, press Y, then double-click start-gauntlet.bat again when it's done.
   Keep the black window open while you use Gauntlet.

3. Your browser opens. Paste your API key into the big box (click in it and press Ctrl+V).
   No key yet? Press "Get an OpenRouter key": one key works for every AI.

4. Next time: double-click "Gauntlet" on your desktop.

5. To update: unblock the new zip (step 1), extract it anywhere and double-click its start-gauntlet.bat.
   Your keys, runs and settings are kept (they live in C:\Users\<you>\AppData\Roaming\Gauntlet).

Blocked even after Unblock? Open the extracted folder, click the address bar, type powershell,
   press Enter, paste:  Get-ChildItem -Recurse | Unblock-File   press Enter, then try again.

Stuck? Open SETUP-WINDOWS.md (right-click, Open with, Notepad) for the troubleshooting list.
