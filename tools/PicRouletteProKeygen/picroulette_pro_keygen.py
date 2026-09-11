import base64
import csv
import datetime as dt
from pathlib import Path
import tkinter as tk
from tkinter import messagebox, ttk

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

APP_DIR = Path(__file__).resolve().parent
PRIVATE_KEY_FILE = APP_DIR / "picroulette_private_key.pem"
HISTORY_FILE = APP_DIR / "generated_codes.csv"
SIGNING_DOMAIN = "PICROULETTE-PRO-V1|"


def normalize_installation_id(value: str) -> str:
    value = value.strip().upper().replace(" ", "")
    if not value.startswith("PR-"):
        raise ValueError("Installation ID must start with PR-")
    return value


def load_private_key():
    if not PRIVATE_KEY_FILE.exists():
        raise FileNotFoundError(f"Missing private key: {PRIVATE_KEY_FILE.name}")
    return serialization.load_pem_private_key(PRIVATE_KEY_FILE.read_bytes(), password=None)


def generate_code(installation_id: str) -> str:
    installation_id = normalize_installation_id(installation_id)
    payload = (SIGNING_DOMAIN + installation_id).encode("utf-8")
    signature = load_private_key().sign(payload, ec.ECDSA(hashes.SHA256()))
    encoded = base64.urlsafe_b64encode(signature).decode("ascii").rstrip("=")
    return "PRA1-" + encoded


def save_history(installation_id: str, code: str, note: str):
    new_file = not HISTORY_FILE.exists()
    with HISTORY_FILE.open("a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        if new_file:
            writer.writerow(["generated_at", "installation_id", "activation_code", "note"])
        writer.writerow([dt.datetime.now().astimezone().isoformat(timespec="seconds"), installation_id, code, note.strip()])


class KeygenApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("PicRoulette Pro Keygen")
        self.geometry("760x410")
        self.minsize(680, 380)

        frame = ttk.Frame(self, padding=20)
        frame.pack(fill="both", expand=True)
        frame.columnconfigure(0, weight=1)

        ttk.Label(frame, text="PicRoulette Pro Keygen", font=("Segoe UI", 18, "bold")).grid(row=0, column=0, sticky="w")
        ttk.Label(frame, text="Generate an offline Pro code tied to one PicRoulette installation.").grid(row=1, column=0, sticky="w", pady=(2, 18))

        ttk.Label(frame, text="Installation ID").grid(row=2, column=0, sticky="w")
        self.installation_id = tk.StringVar()
        ttk.Entry(frame, textvariable=self.installation_id, font=("Consolas", 12)).grid(row=3, column=0, sticky="ew", pady=(4, 12))

        ttk.Label(frame, text="Note (optional)").grid(row=4, column=0, sticky="w")
        self.note = tk.StringVar()
        ttk.Entry(frame, textvariable=self.note).grid(row=5, column=0, sticky="ew", pady=(4, 14))

        ttk.Button(frame, text="Generate Lifetime Pro Code", command=self.generate).grid(row=6, column=0, sticky="ew")

        ttk.Label(frame, text="Activation code").grid(row=7, column=0, sticky="w", pady=(16, 4))
        self.code = tk.Text(frame, height=4, wrap="word", font=("Consolas", 10))
        self.code.grid(row=8, column=0, sticky="nsew")
        frame.rowconfigure(8, weight=1)

        buttons = ttk.Frame(frame)
        buttons.grid(row=9, column=0, sticky="ew", pady=(10, 0))
        ttk.Button(buttons, text="Copy Code", command=self.copy_code).pack(side="left")
        ttk.Button(buttons, text="Open History Folder", command=self.open_history_folder).pack(side="left", padx=(8, 0))

    def generate(self):
        try:
            installation_id = normalize_installation_id(self.installation_id.get())
            code = generate_code(installation_id)
            save_history(installation_id, code, self.note.get())
            self.code.delete("1.0", "end")
            self.code.insert("1.0", code)
            self.clipboard_clear()
            self.clipboard_append(code)
            messagebox.showinfo("Generated", "Lifetime Pro code generated and copied to the clipboard.")
        except Exception as exc:
            messagebox.showerror("Could not generate code", str(exc))

    def copy_code(self):
        code = self.code.get("1.0", "end").strip()
        if code:
            self.clipboard_clear()
            self.clipboard_append(code)

    def open_history_folder(self):
        import os
        os.startfile(APP_DIR)


if __name__ == "__main__":
    KeygenApp().mainloop()
