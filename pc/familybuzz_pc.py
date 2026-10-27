"""
Family Buzz for Windows.

One app with two tabs:
  * Receive - this PC gets its own pairing code (like a kid's phone). Messages pop up,
              ring, or take over the whole screen until someone clicks "Got it".
  * Send    - send messages to kids' phones or other PCs (like the parent app).

Uses the same free ntfy.sh relay and message format as the Android apps.
"""

import json
import math
import os
import queue
import secrets
import struct
import sys
import threading
import time
import urllib.parse
import urllib.request
import wave
from datetime import datetime

import tkinter as tk
from tkinter import messagebox, simpledialog, ttk

APP = "Family Buzz"
BASE = "https://ntfy.sh"
ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
STALE_SECONDS = 15 * 60
IS_WINDOWS = sys.platform == "win32"

QUICK_PHRASES = [
    "Dinner's ready!", "Come here please", "Call me now", "Time to put it away",
    "Time for bed", "We're leaving in 5", "Check your messages",
]
LEVELS = [
    ("gentle", "Gentle - a normal pop-up"),
    ("loud", "Loud - alarm sound for 10 seconds"),
    ("takeover", "Take over the screen - until they click \"Got it\""),
]


# ---------------------------------------------------------------- storage

def data_dir() -> str:
    root = os.environ.get("APPDATA") or os.path.expanduser("~/.config")
    d = os.path.join(root, "FamilyBuzz")
    os.makedirs(d, exist_ok=True)
    return d


class Config:
    def __init__(self):
        self.path = os.path.join(data_dir(), "config.json")
        self.lock = threading.Lock()
        try:
            with open(self.path, encoding="utf-8") as f:
                self.data = json.load(f)
        except Exception:
            self.data = {}
        self.data.setdefault("topic", "fam-" + rand(18))
        self.data.setdefault("deviceName", os.environ.get("USERNAME", "") + "'s PC")
        self.data.setdefault("myName", "")
        self.data.setdefault("kids", [])
        self.data.setdefault("history", [])
        self.data.setdefault("seenIds", [])
        self.data.setdefault("lastLevel", "loud")
        self.save()

    def __getitem__(self, k):
        return self.data[k]

    def __setitem__(self, k, v):
        self.data[k] = v
        self.save()

    def save(self):
        with self.lock:
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.data, f, indent=2)
            os.replace(tmp, self.path)


def rand(n: int) -> str:
    return "".join(secrets.choice(ALPHABET) for _ in range(n))


# ---------------------------------------------------------------- ntfy

def publish(topic: str, body: str) -> bool:
    try:
        req = urllib.request.Request(f"{BASE}/{topic}", data=body.encode("utf-8"), method="POST",
                                     headers={"Content-Type": "text/plain; charset=utf-8"})
        with urllib.request.urlopen(req, timeout=15) as r:
            return 200 <= r.status < 300
    except Exception:
        return False


def poll(topic: str, since: str = "12h") -> list:
    out = []
    try:
        url = f"{BASE}/{topic}/json?poll=1&since={urllib.parse.quote(since)}"
        with urllib.request.urlopen(url, timeout=15) as r:
            for line in r:
                line = line.strip()
                if line:
                    o = json.loads(line)
                    if o.get("event") == "message":
                        out.append(o)
    except Exception:
        pass
    return out


def make_buzz(sender: str, text: str, level: str) -> dict:
    return {"app": "familybuzz", "id": rand(12), "from": sender, "text": text,
            "level": level, "sentAt": int(time.time() * 1000)}


def parse_buzz(s: str):
    try:
        o = json.loads(s)
        if o.get("app") != "familybuzz" or "id" not in o:
            return None
        return o
    except Exception:
        return None


def ack_topic(topic: str) -> str:
    return topic + "-ack"


class Listener(threading.Thread):
    """Keeps a streaming connection to ntfy.sh and hands each message to on_buzz."""

    def __init__(self, cfg: Config, on_buzz, on_status):
        super().__init__(daemon=True)
        self.cfg, self.on_buzz, self.on_status = cfg, on_buzz, on_status

    def run(self):
        backoff = 2
        while True:
            try:
                since = self.cfg.data.get("lastId") or "1m"
                url = f"{BASE}/{self.cfg['topic']}/json?since={since}"
                with urllib.request.urlopen(url, timeout=90) as r:
                    self.on_status(True)
                    backoff = 2
                    for line in r:
                        line = line.strip()
                        if not line:
                            continue
                        o = json.loads(line)
                        if o.get("event") != "message":
                            continue
                        self.cfg["lastId"] = o["id"]
                        b = parse_buzz(o.get("message", ""))
                        if b:
                            self.on_buzz(b, o.get("time", time.time()))
            except Exception:
                pass
            self.on_status(False)
            time.sleep(backoff)
            backoff = min(backoff * 2, 60)


# ---------------------------------------------------------------- sound

def alarm_wav() -> str:
    """Creates a short two-tone alarm sound the first time it's needed."""
    path = os.path.join(data_dir(), "alarm.wav")
    if os.path.exists(path):
        return path
    rate = 22050
    frames = bytearray()
    for tone in (988, 740, 988, 740):
        for i in range(int(rate * 0.25)):
            env = min(1.0, i / 200, (rate * 0.25 - i) / 200)
            frames += struct.pack("<h", int(26000 * env * math.sin(2 * math.pi * tone * i / rate)))
    frames += b"\x00\x00" * int(rate * 0.3)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(bytes(frames))
    return path


class Sound:
    def __init__(self):
        self.timer = None

    def play(self, seconds: float = 0):
        """seconds = 0 keeps ringing until stop()."""
        self.stop()
        if IS_WINDOWS:
            import winsound
            winsound.PlaySound(alarm_wav(), winsound.SND_FILENAME | winsound.SND_ASYNC | winsound.SND_LOOP)
        if seconds:
            self.timer = threading.Timer(seconds, self.stop)
            self.timer.daemon = True
            self.timer.start()

    def chime(self):
        if IS_WINDOWS:
            import winsound
            winsound.MessageBeep(winsound.MB_ICONASTERISK)

    def stop(self):
        if self.timer:
            self.timer.cancel()
            self.timer = None
        if IS_WINDOWS:
            import winsound
            winsound.PlaySound(None, 0)


# ---------------------------------------------------------------- windows helpers

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def startup_enabled() -> bool:
    if not IS_WINDOWS:
        return False
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, "FamilyBuzz")
            return True
    except OSError:
        return False


def set_startup(on: bool):
    if not IS_WINDOWS:
        return
    import winreg
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if on:
            if getattr(sys, "frozen", False):
                cmd = f'"{sys.executable}" --hidden'
            else:
                cmd = f'"{sys.executable}" "{os.path.abspath(__file__)}" --hidden'
            winreg.SetValueEx(k, "FamilyBuzz", 0, winreg.REG_SZ, cmd)
        else:
            try:
                winreg.DeleteValue(k, "FamilyBuzz")
            except OSError:
                pass


def single_instance() -> bool:
    if not IS_WINDOWS:
        return True
    import ctypes
    ctypes.windll.kernel32.CreateMutexW(None, False, "FamilyBuzzSingleInstance")
    return ctypes.windll.kernel32.GetLastError() != 183  # ERROR_ALREADY_EXISTS


# ---------------------------------------------------------------- app

class App:
    def __init__(self, start_hidden: bool):
        self.cfg = Config()
        self.sound = Sound()
        self.ui_q = queue.Queue()
        self.connected = False
        self.takeover_win = None
        self.takeover_pending = []
        self.selected = {}

        self.root = tk.Tk()
        self.root.title(APP)
        self.root.geometry("560x720")
        self.root.minsize(460, 560)
        self.root.protocol("WM_DELETE_WINDOW", self.hide)
        style = ttk.Style(self.root)
        if "vista" in style.theme_names():
            style.theme_use("vista")
        style.configure("Big.TButton", font=("Segoe UI", 12, "bold"), padding=8)
        style.configure("Code.TLabel", font=("Consolas", 18, "bold"))
        style.configure("H.TLabel", font=("Segoe UI", 10, "bold"))

        nb = ttk.Notebook(self.root)
        nb.pack(fill="both", expand=True, padx=8, pady=8)
        self.build_receive(nb)
        self.build_send(nb)

        self.tray = None
        self.start_tray()
        if start_hidden:
            self.root.withdraw()

        Listener(self.cfg, self.on_buzz_thread, self.on_status_thread).start()
        self.root.after(200, self.pump)
        self.root.after(1500, self.refresh_acks)

    # ---------- thread plumbing
    def later(self, fn):
        self.ui_q.put(fn)

    def pump(self):
        try:
            while True:
                self.ui_q.get_nowait()()
        except queue.Empty:
            pass
        self.root.after(200, self.pump)

    def on_status_thread(self, ok):
        self.later(lambda: self.set_status(ok))

    def on_buzz_thread(self, b, server_time):
        self.later(lambda: self.handle(b, server_time))

    # ---------- receive tab
    def build_receive(self, nb):
        f = ttk.Frame(nb, padding=14)
        nb.add(f, text="  Receive  ")

        row = ttk.Frame(f)
        row.pack(fill="x")
        self.dev_label = ttk.Label(row, font=("Segoe UI", 13, "bold"))
        self.dev_label.pack(side="left")
        ttk.Button(row, text="Rename", command=self.rename_device).pack(side="right")
        self.update_device_label()

        ttk.Label(f, text="PAIRING CODE", style="H.TLabel").pack(anchor="w", pady=(18, 4))
        ttk.Label(f, text=self.cfg["topic"], style="Code.TLabel").pack(anchor="w")
        ttk.Label(f, text="Add this code in the Family Buzz app on each parent's phone\n"
                          "(or the Send tab of another PC), just like a kid's phone.",
                  foreground="#555").pack(anchor="w", pady=(4, 6))
        ttk.Button(f, text="Copy code", command=self.copy_code).pack(anchor="w")

        ttk.Label(f, text="STATUS", style="H.TLabel").pack(anchor="w", pady=(18, 4))
        self.status_label = ttk.Label(f, text="Connecting...", font=("Segoe UI", 11))
        self.status_label.pack(anchor="w")

        ttk.Label(f, text="SETTINGS", style="H.TLabel").pack(anchor="w", pady=(18, 4))
        self.startup_var = tk.BooleanVar(value=startup_enabled())
        ttk.Checkbutton(f, text="Start automatically when Windows starts (runs in the tray)",
                        variable=self.startup_var,
                        command=lambda: set_startup(self.startup_var.get())).pack(anchor="w")
        ttk.Label(f, text="Closing this window keeps Family Buzz running in the system tray.",
                  foreground="#555").pack(anchor="w", pady=(4, 0))

        ttk.Label(f, text="TRY IT", style="H.TLabel").pack(anchor="w", pady=(18, 4))
        t = ttk.Frame(f)
        t.pack(fill="x")
        for lvl, name in (("gentle", "Gentle"), ("loud", "Loud"), ("takeover", "Take over")):
            ttk.Button(t, text=name, command=lambda l=lvl: self.test(l)).pack(side="left", padx=(0, 6))

    def update_device_label(self):
        self.dev_label.config(text=self.cfg["deviceName"] or "This PC")

    def rename_device(self):
        n = simpledialog.askstring(APP, "Name for this PC (parents see this when you tap Got it):",
                                   initialvalue=self.cfg["deviceName"], parent=self.root)
        if n is not None:
            self.cfg["deviceName"] = n.strip()
            self.update_device_label()

    def copy_code(self):
        self.root.clipboard_clear()
        self.root.clipboard_append(self.cfg["topic"])

    def set_status(self, ok):
        self.connected = ok
        self.status_label.config(text="● Connected - ready for messages" if ok else "○ Connecting... (check the internet)",
                                 foreground="#2E7D32" if ok else "#C62828")

    def test(self, level):
        self.handle(make_buzz("Test", f"This is what a {level} message looks like.", level) | {"id": "test-" + rand(8)},
                    time.time())

    # ---------- alerts
    def handle(self, b, server_time):
        seen = self.cfg["seenIds"]
        if b["id"] in seen:
            return
        self.cfg["seenIds"] = (seen + [b["id"]])[-50:]
        sent = b.get("sentAt", 0) / 1000
        late = time.time() - min(sent or server_time, server_time) > STALE_SECONDS
        level = "gentle" if late else b.get("level", "loud")
        title = b.get("from", "Parent")
        if late:
            title += f" (sent at {datetime.fromtimestamp(sent).strftime('%I:%M %p').lstrip('0')})"
        if level == "takeover":
            self.show_takeover(b["id"], title, b.get("text", ""))
        else:
            self.show_popup(b["id"], title, b.get("text", ""), loud=(level == "loud"))

    def ack(self, mid):
        if mid.startswith("test-"):
            return
        body = json.dumps({"ack": mid, "kid": self.cfg["deviceName"], "at": int(time.time() * 1000)})
        threading.Thread(target=publish, args=(ack_topic(self.cfg["topic"]), body), daemon=True).start()

    def show_popup(self, mid, title, text, loud):
        w = tk.Toplevel(self.root)
        w.title(APP)
        w.attributes("-topmost", True)
        w.configure(bg="#E65100" if loud else "#2E5BBA")
        w.resizable(False, False)
        tk.Label(w, text=title, bg=w["bg"], fg="white", font=("Segoe UI", 13, "bold")).pack(padx=24, pady=(18, 4))
        tk.Label(w, text=text, bg=w["bg"], fg="white", font=("Segoe UI", 16), wraplength=380,
                 justify="center").pack(padx=24, pady=(0, 14))

        def done():
            self.sound.stop()
            self.ack(mid)
            w.destroy()

        tk.Button(w, text="Got it", font=("Segoe UI", 12, "bold"), width=12, command=done).pack(pady=(0, 18))
        w.protocol("WM_DELETE_WINDOW", lambda: (self.sound.stop(), w.destroy()))
        w.update_idletasks()
        sw, sh = w.winfo_screenwidth(), w.winfo_screenheight()
        w.geometry(f"+{sw - w.winfo_width() - 30}+{sh - w.winfo_height() - 80}")
        w.lift()
        w.focus_force()
        if loud:
            self.sound.play(10)
        else:
            self.sound.chime()

    def show_takeover(self, mid, title, text):
        self.takeover_pending.append(mid)
        self.sound.play(0)
        if self.takeover_win is not None:
            self.to_from.config(text=title)
            self.to_text.config(text=text)
            return
        w = tk.Toplevel(self.root)
        self.takeover_win = w
        w.configure(bg="#D84315")
        w.attributes("-fullscreen", True)
        w.attributes("-topmost", True)
        w.protocol("WM_DELETE_WINDOW", lambda: None)  # can't just close it
        w.bind("<Alt-F4>", lambda e: "break")
        box = tk.Frame(w, bg="#D84315")
        box.place(relx=0.5, rely=0.5, anchor="center")
        tk.Label(box, text="📣", bg="#D84315", fg="white", font=("Segoe UI Emoji", 80)).pack()
        self.to_from = tk.Label(box, text=title, bg="#D84315", fg="white", font=("Segoe UI", 30, "bold"))
        self.to_from.pack(pady=(10, 0))
        self.to_text = tk.Label(box, text=text, bg="#D84315", fg="white", font=("Segoe UI", 44, "bold"),
                                wraplength=int(w.winfo_screenwidth() * 0.8), justify="center")
        self.to_text.pack(pady=(10, 50))
        tk.Button(box, text="Got it", font=("Segoe UI", 24, "bold"), width=14,
                  command=self.takeover_done).pack()

        def keep_on_top():
            if self.takeover_win is w:
                w.attributes("-topmost", True)
                w.lift()
                w.focus_force()
                w.after(1000, keep_on_top)

        keep_on_top()

    def takeover_done(self):
        self.sound.stop()
        for mid in self.takeover_pending:
            self.ack(mid)
        self.takeover_pending = []
        if self.takeover_win is not None:
            self.takeover_win.destroy()
            self.takeover_win = None

    # ---------- send tab
    def build_send(self, nb):
        f = ttk.Frame(nb, padding=14)
        nb.add(f, text="  Send  ")

        row = ttk.Frame(f)
        row.pack(fill="x")
        self.from_label = ttk.Label(row, font=("Segoe UI", 11))
        self.from_label.pack(side="left")
        ttk.Button(row, text="Change", command=self.edit_my_name).pack(side="right")
        self.update_from_label()

        ttk.Label(f, text="SEND TO", style="H.TLabel").pack(anchor="w", pady=(14, 4))
        self.kids_frame = ttk.Frame(f)
        self.kids_frame.pack(fill="x")
        kb = ttk.Frame(f)
        kb.pack(fill="x", pady=(4, 0))
        ttk.Button(kb, text="+ Add a device", command=self.add_kid).pack(side="left")
        ttk.Button(kb, text="Remove...", command=self.remove_kid).pack(side="left", padx=6)
        self.render_kids()

        ttk.Label(f, text="MESSAGE", style="H.TLabel").pack(anchor="w", pady=(14, 4))
        chips = ttk.Frame(f)
        chips.pack(fill="x")
        for i, p in enumerate(QUICK_PHRASES):
            ttk.Button(chips, text=p, command=lambda p=p: self.set_msg(p)).grid(row=i // 3, column=i % 3, sticky="ew", padx=2, pady=2)
        for c in range(3):
            chips.columnconfigure(c, weight=1)
        self.msg = tk.Text(f, height=3, font=("Segoe UI", 11), wrap="word")
        self.msg.pack(fill="x", pady=(6, 0))

        ttk.Label(f, text="HOW LOUD", style="H.TLabel").pack(anchor="w", pady=(14, 4))
        self.level = tk.StringVar(value=self.cfg["lastLevel"])
        for val, label in LEVELS:
            ttk.Radiobutton(f, text=label, value=val, variable=self.level).pack(anchor="w")

        ttk.Button(f, text="Send", style="Big.TButton", command=self.send).pack(fill="x", pady=(14, 6))

        ttk.Label(f, text="RECENT", style="H.TLabel").pack(anchor="w", pady=(8, 4))
        self.hist = tk.Listbox(f, height=8, font=("Segoe UI", 10), activestyle="none")
        self.hist.pack(fill="both", expand=True)
        self.render_history()

    def update_from_label(self):
        n = self.cfg["myName"]
        self.from_label.config(text=f"Sending as: {n}" if n else "Sending as: (set your name)")

    def edit_my_name(self):
        n = simpledialog.askstring(APP, "Your name (the kids see this on each message):",
                                   initialvalue=self.cfg["myName"], parent=self.root)
        if n is not None:
            self.cfg["myName"] = n.strip()
            self.update_from_label()

    def render_kids(self):
        for c in self.kids_frame.winfo_children():
            c.destroy()
        if not self.cfg["kids"]:
            ttk.Label(self.kids_frame, text="No devices added yet. Add the code shown in the Buzz app on a kid's phone\n"
                                            "or on another PC's Receive tab.", foreground="#555").pack(anchor="w")
            return
        for k in self.cfg["kids"]:
            v = self.selected.setdefault(k["topic"], tk.BooleanVar(value=True))
            ttk.Checkbutton(self.kids_frame, text=k["name"], variable=v).pack(anchor="w")

    def add_kid(self):
        name = simpledialog.askstring(APP, "Name (e.g. Emma's phone):", parent=self.root)
        if not name:
            return
        try:
            clip = self.root.clipboard_get()
        except tk.TclError:
            clip = ""
        guess = next((w for w in clip.lower().replace(":", " ").split() if w.startswith("fam-")), "")
        code = simpledialog.askstring(APP, "Their code (starts with fam-):", initialvalue=guess, parent=self.root)
        if not code:
            return
        code = code.strip().lower()
        if not (code.startswith("fam-") and 12 <= len(code) <= 44 and code[4:].isalnum()):
            messagebox.showerror(APP, "That code doesn't look right. It starts with fam-", parent=self.root)
            return
        self.cfg["kids"] = [k for k in self.cfg["kids"] if k["topic"] != code] + [{"name": name.strip(), "topic": code}]
        self.selected[code] = tk.BooleanVar(value=True)
        self.render_kids()

    def remove_kid(self):
        names = [k["name"] for k in self.cfg["kids"]]
        if not names:
            return
        n = simpledialog.askstring(APP, "Type the name to remove:\n" + "\n".join(names), parent=self.root)
        if n:
            self.cfg["kids"] = [k for k in self.cfg["kids"] if k["name"].lower() != n.strip().lower()]
            self.render_kids()

    def set_msg(self, p):
        self.msg.delete("1.0", "end")
        self.msg.insert("1.0", p)

    def send(self):
        text = self.msg.get("1.0", "end").strip()
        targets = [k for k in self.cfg["kids"] if self.selected.get(k["topic"]) and self.selected[k["topic"]].get()]
        if not targets:
            messagebox.showinfo(APP, "Pick at least one device.", parent=self.root)
            return
        if not text:
            messagebox.showinfo(APP, "Type a message or click a quick phrase.", parent=self.root)
            return
        level = self.level.get()
        self.cfg["lastLevel"] = level
        sender = self.cfg["myName"] or "Parent"

        def work():
            entries = []
            for k in targets:
                b = make_buzz(sender, text, level)
                ok = publish(k["topic"], json.dumps(b))
                entries.append({"mid": b["id"], "kid": k["name"], "topic": k["topic"], "text": text,
                                "at": b["sentAt"], "ok": ok})
            self.later(lambda: self.after_send(entries))

        threading.Thread(target=work, daemon=True).start()

    def after_send(self, entries):
        self.cfg["history"] = (entries + self.cfg["history"])[:30]
        self.render_history()
        failed = sum(1 for e in entries if not e["ok"])
        if failed:
            messagebox.showwarning(APP, f"Couldn't reach {failed} device(s). Check the internet.", parent=self.root)
        else:
            self.msg.delete("1.0", "end")
        self.poll_until = time.time() + 120

    def render_history(self):
        self.hist.delete(0, "end")
        if not self.cfg["history"]:
            self.hist.insert("end", "Nothing sent yet.")
            return
        for e in self.cfg["history"]:
            t = datetime.fromtimestamp(e["at"] / 1000).strftime("%I:%M %p").lstrip("0")
            if e.get("ackAt"):
                st = "✓ seen " + datetime.fromtimestamp(e["ackAt"] / 1000).strftime("%I:%M %p").lstrip("0")
            elif not e.get("ok", True):
                st = "✗ didn't send"
            else:
                st = "… waiting"
            self.hist.insert("end", f"{t}  {e['kid']}: {e['text']}   [{st}]")

    def refresh_acks(self):
        topics = {e["topic"] for e in self.cfg["history"] if not e.get("ackAt") and e.get("ok", True)}

        def work():
            acks = {}
            for t in topics:
                for m in poll(ack_topic(t)):
                    try:
                        o = json.loads(m.get("message", ""))
                        acks[o["ack"]] = o.get("at", m.get("time", 0) * 1000)
                    except Exception:
                        pass
            if acks:
                self.later(lambda: self.apply_acks(acks))

        if topics:
            threading.Thread(target=work, daemon=True).start()
        fast = time.time() < getattr(self, "poll_until", 0)
        self.root.after(8000 if fast else 60000, self.refresh_acks)

    def apply_acks(self, acks):
        changed = False
        hist = self.cfg["history"]
        for e in hist:
            if not e.get("ackAt") and e["mid"] in acks:
                e["ackAt"] = acks[e["mid"]]
                changed = True
        if changed:
            self.cfg["history"] = hist
            self.render_history()

    # ---------- window / tray
    def show(self):
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def hide(self):
        if self.tray is None:
            if messagebox.askyesno(APP, "Quit Family Buzz? This PC will stop receiving messages.", parent=self.root):
                self.quit()
            return
        self.root.withdraw()

    def quit(self):
        self.sound.stop()
        if self.tray is not None:
            try:
                self.tray.stop()
            except Exception:
                pass
        self.root.destroy()

    def start_tray(self):
        try:
            import pystray
            from PIL import Image, ImageDraw
        except Exception:
            return
        img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        d.ellipse((2, 2, 62, 62), fill=(230, 81, 0, 255))
        d.ellipse((22, 14, 42, 44), fill="white")
        d.rectangle((18, 34, 46, 44), fill="white")
        d.ellipse((27, 44, 37, 54), fill="white")
        menu = pystray.Menu(
            pystray.MenuItem("Open Family Buzz", lambda: self.later(self.show), default=True),
            pystray.MenuItem("Quit", lambda: self.later(self.quit)),
        )
        self.tray = pystray.Icon("FamilyBuzz", img, APP, menu)
        self.tray.run_detached()

    def run(self):
        self.root.mainloop()


def main():
    if IS_WINDOWS:
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:
            pass
    if not single_instance():
        messagebox.showinfo(APP, "Family Buzz is already running. Look for its icon in the system tray.")
        return
    App(start_hidden="--hidden" in sys.argv).run()


if __name__ == "__main__":
    main()
