import asyncio
import os
import socket
import threading
import time
import uuid
import mimetypes
import tkinter as tk
from tkinter import filedialog, messagebox
from pathlib import Path
from datetime import datetime
import urllib.request
import io

from aiohttp import web


# ─────────────────────────────────────────────────────────────────────────────
#  Design tokens — industrial amber-on-charcoal terminal aesthetic
# ─────────────────────────────────────────────────────────────────────────────
C = {
    'bg':          '#1a1a1a',
    'surface':     '#242424',
    'surface2':    '#2e2e2e',
    'border':      '#383838',
    'border_hi':   '#505050',
    'amber':       '#f59e0b',
    'amber_dim':   '#7a4f05',
    'amber_glow':  '#fbbf24',
    'green':       '#22c55e',
    'red':         '#ef4444',
    'text':        '#e5e5e5',
    'text_dim':    '#737373',
    'text_faint':  '#404040',
}


# ─────────────────────────────────────────────────────────────────────────────
#  Data model
# ─────────────────────────────────────────────────────────────────────────────
class Device:
    def __init__(self, name: str, address: str, http_port: int, device_id: str):
        self.name       = name
        self.address    = address
        self.http_port  = http_port
        self.device_id  = device_id
        self.last_seen  = datetime.now()


# ─────────────────────────────────────────────────────────────────────────────
#  UDP discovery — matches Android side protocol exactly
# ─────────────────────────────────────────────────────────────────────────────
class UDPDiscovery:
    DISCOVERY_PORT = 8766

    def __init__(self, device_id, device_name, http_port, on_device_found):
        self.device_id       = device_id
        self.device_name     = device_name
        self.http_port       = http_port
        self.on_device_found = on_device_found
        self.running         = False
        self._sock           = None
        self._stop_event     = threading.Event()

    def start(self):
        self.running = True
        self._stop_event.clear()
        threading.Thread(target=self._listen,    daemon=True).start()
        threading.Thread(target=self._broadcast, daemon=True).start()

    def _listen(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            s.settimeout(2)
            s.bind(('', self.DISCOVERY_PORT))
            self._sock = s
            while self.running:
                try:
                    data, addr = s.recvfrom(1024)
                    self._handle(data, addr[0])
                except socket.timeout:
                    continue
                except Exception:
                    if self.running:
                        continue
                    break
        except Exception as e:
            print(f"[UDP] listen error: {e}")

    def _broadcast(self):
        msg = f"PYDROP_ANNOUNCE|{self.device_id}|{self.device_name}|{self.http_port}".encode()
        while self.running:
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                    s.sendto(msg, ('255.255.255.255', self.DISCOVERY_PORT))
            except Exception:
                pass
            self._stop_event.wait(3)  # wakes immediately on stop()

    def _handle(self, data: bytes, addr: str):
        try:
            msg = data.decode().strip()
            if not msg.startswith("PYDROP_ANNOUNCE"):
                return
            parts = msg.split("|")
            if len(parts) < 4:
                return
            if parts[1] == self.device_id:
                return  # own packet
            device = Device(parts[2], addr, int(parts[3]), parts[1])
            self.on_device_found(device)
        except Exception:
            pass

    def stop(self):
        self.running = False
        self._stop_event.set()
        if self._sock:
            self._sock.close()


# ─────────────────────────────────────────────────────────────────────────────
#  HTTP file receiver — POST /api/upload (aiohttp multipart)
# ─────────────────────────────────────────────────────────────────────────────
class FileReceiver:
    def __init__(self, http_port, on_file_received, get_save_dir):
        self.http_port        = http_port
        self.on_file_received = on_file_received
        self.get_save_dir     = get_save_dir
        self.running          = False

    def start(self):
        self.running = True
        threading.Thread(target=lambda: asyncio.run(self._serve()), daemon=True).start()

    async def _serve(self):
        app = web.Application()
        app.router.add_post('/api/upload', self._handle_upload)
        app.router.add_get('/api/info',    self._handle_info)
        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, '0.0.0.0', self.http_port)
        await site.start()
        while self.running:
            await asyncio.sleep(1)

    async def _handle_info(self, _request):
        return web.json_response({'status': 'ok'})

    async def _handle_upload(self, request):
        try:
            reader = await request.multipart()
            field  = await reader.next()
            if not field:
                return web.Response(text='No file', status=400)

            filename = os.path.basename(field.filename or f"file_{int(datetime.now().timestamp())}")
            if not filename:
                filename = f"file_{int(datetime.now().timestamp())}"
            save_dir = Path(self.get_save_dir())
            save_dir.mkdir(parents=True, exist_ok=True)

            file_id = uuid.uuid4().hex[:8]
            dest    = save_dir / filename
            if dest.exists():
                dest = save_dir / f"{dest.stem}_{file_id}{dest.suffix}"

            size = 0
            with open(dest, 'wb') as f:
                while True:
                    chunk = await field.read_chunk(65536)
                    if not chunk:
                        break
                    f.write(chunk)
                    size += len(chunk)

            self.on_file_received({
                'id':   file_id,
                'name': filename,
                'size': size,
                'path': str(dest),
            })
            return web.json_response({'success': True, 'fileId': file_id})
        except Exception as e:
            print(f"[HTTP] upload error: {e}")
            return web.Response(text=str(e), status=500)

    def stop(self):
        self.running = False


# ─────────────────────────────────────────────────────────────────────────────
#  File sender (runs on background thread)
# ─────────────────────────────────────────────────────────────────────────────
def send_file(device: Device, file_path: str) -> bool:
    try:
        filename  = os.path.basename(file_path)
        mime_type = mimetypes.guess_type(file_path)[0] or 'application/octet-stream'
        boundary  = '----PyDropBoundary' + uuid.uuid4().hex

        with open(file_path, 'rb') as f:
            file_data = f.read()

        body  = f'--{boundary}\r\n'.encode()
        body += f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode()
        body += f'Content-Type: {mime_type}\r\n\r\n'.encode()
        body += file_data
        body += f'\r\n--{boundary}--\r\n'.encode()

        req = urllib.request.Request(
            f"http://{device.address}:{device.http_port}/api/upload",
            data=body, method='POST',
            headers={
                'Content-Type':   f'multipart/form-data; boundary={boundary}',
                'Content-Length': str(len(body)),
            }
        )
        urllib.request.urlopen(req, timeout=120)
        return True
    except Exception as e:
        print(f"[SEND] error: {e}")
        return False


# ─────────────────────────────────────────────────────────────────────────────
#  Utilities
# ─────────────────────────────────────────────────────────────────────────────
def get_local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('10.255.255.255', 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return '127.0.0.1'


def fmt_size(b: int) -> str:
    n: float = float(b)
    for unit in ['B', 'KB', 'MB', 'GB']:
        if n < 1024:
            return f"{int(n)} {unit}" if unit == 'B' else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


def truncate_path(path: str, max_len: int = 50) -> str:
    if len(path) <= max_len:
        return path
    return '\u2026' + path[-(max_len - 1):]


# ─────────────────────────────────────────────────────────────────────────────
#  GUI — industrial amber terminal aesthetic
# ─────────────────────────────────────────────────────────────────────────────
class PyDropApp:
    HTTP_PORT = 8080

    def __init__(self):
        self.device_id   = uuid.uuid4().hex[:12]
        self.device_name = socket.gethostname()
        self.local_ip    = get_local_ip()
        self.save_dir    = str(Path.home() / 'Downloads' / 'PyDrop')
        self.devices: dict[str, Device] = {}

        # Backend
        self.discovery = UDPDiscovery(
            self.device_id, self.device_name, self.HTTP_PORT,
            self._on_device_found
        )
        self.receiver = FileReceiver(
            self.HTTP_PORT, self._on_file_received, lambda: self.save_dir
        )

        # Root window
        self.root = tk.Tk()
        self.root.title("PyDrop")
        self.root.configure(bg=C['bg'])
        self.root.resizable(True, True)
        self.root.geometry("430x560")
        self.root.minsize(360, 480)

        # Fonts (Consolas ships with Windows — perfect monospace terminal feel)
        self.f_title   = ('Consolas', 16, 'bold')
        self.f_section = ('Consolas',  8, 'normal')
        self.f_mono    = ('Consolas',  9, 'normal')
        self.f_btn     = ('Consolas', 10, 'bold')
        self.f_device  = ('Consolas', 11, 'bold')
        self.f_ip      = ('Consolas',  9, 'normal')
        self.f_status  = ('Consolas', 10, 'bold')

        self._build_ui()
        self._start_backend()
        self.root.mainloop()

    # ── Build UI ──────────────────────────────────────────────────────────────
    def _build_ui(self):
        PAD = 14

        # ── Title strip ───────────────────────────────────────────────────────
        title_strip = tk.Frame(self.root, bg=C['surface'], height=52)
        title_strip.pack(fill='x')
        title_strip.pack_propagate(False)

        tk.Label(
            title_strip, text="PY", font=self.f_title,
            bg=C['surface'], fg=C['amber']
        ).pack(side='left', padx=(PAD, 0), pady=14)
        tk.Label(
            title_strip, text="DROP", font=self.f_title,
            bg=C['surface'], fg=C['text']
        ).pack(side='left', pady=14)

        # IP + hostname right-aligned
        tk.Label(
            title_strip,
            text=f"{self.device_name}  {self.local_ip}",
            font=self.f_ip,
            bg=C['surface'], fg=C['text_faint']
        ).pack(side='right', padx=PAD)

        # Amber hairline separator
        tk.Frame(self.root, bg=C['amber'], height=2).pack(fill='x')

        # ── Status bar ───────────────────────────────────────────────────────
        status_bar = tk.Frame(self.root, bg=C['surface'], height=36)
        status_bar.pack(fill='x')
        status_bar.pack_propagate(False)

        tk.Label(
            status_bar, text="STATUS", font=self.f_section,
            bg=C['surface'], fg=C['text_faint']
        ).pack(side='left', padx=(PAD, 6), pady=10)

        self._dot = tk.Label(
            status_bar, text="\u25cf", font=('Consolas', 12),
            bg=C['surface'], fg=C['text_faint']
        )
        self._dot.pack(side='left', pady=10)

        self._status_lbl = tk.Label(
            status_bar, text="STARTING\u2026", font=self.f_status,
            bg=C['surface'], fg=C['text_dim']
        )
        self._status_lbl.pack(side='left', padx=(5, 0), pady=10)

        # Thin separator
        tk.Frame(self.root, bg=C['border'], height=1).pack(fill='x')

        # ── Devices section ───────────────────────────────────────────────────
        dev_hdr = tk.Frame(self.root, bg=C['bg'])
        dev_hdr.pack(fill='x', padx=PAD, pady=(10, 6))

        tk.Label(
            dev_hdr, text="NEARBY DEVICES", font=self.f_section,
            bg=C['bg'], fg=C['text_faint']
        ).pack(side='left')

        self._dev_count_lbl = tk.Label(
            dev_hdr, text="0 found", font=self.f_section,
            bg=C['bg'], fg=C['amber_dim']
        )
        self._dev_count_lbl.pack(side='right')

        # Device list container (fixed height, scrollable via inner frame)
        self._dev_list_outer = tk.Frame(
            self.root, bg=C['bg'], height=200
        )
        self._dev_list_outer.pack(fill='x', padx=PAD)
        self._dev_list_outer.pack_propagate(False)

        self._dev_list = tk.Frame(self._dev_list_outer, bg=C['bg'])
        self._dev_list.pack(fill='both', expand=True)

        self._no_dev_lbl = tk.Label(
            self._dev_list,
            text="scanning the network\u2026",
            font=self.f_mono, bg=C['bg'], fg=C['text_faint']
        )
        self._no_dev_lbl.pack(pady=40)

        tk.Frame(self.root, bg=C['border'], height=1).pack(fill='x', pady=(8, 0))

        # ── Action buttons ────────────────────────────────────────────────────
        btn_row = tk.Frame(self.root, bg=C['bg'])
        btn_row.pack(fill='x', padx=PAD, pady=12)

        self._btn_send = self._btn(
            btn_row, "SEND FILE", self._on_send,
            fg=C['bg'], bg=C['amber'], hover=C['amber_glow']
        )
        self._btn_send.pack(side='left', fill='x', expand=True, padx=(0, 6))

        self._btn_dir = self._btn(
            btn_row, "SAVE DIR", self._on_choose_dir,
            fg=C['text'], bg=C['surface2'], hover=C['border_hi']
        )
        self._btn_dir.pack(side='left', fill='x', expand=True, padx=(0, 6))

        self._btn_exit = self._btn(
            btn_row, "EXIT", self._on_exit,
            fg=C['red'], bg=C['surface'], hover='#2e1a1a',
            outline=C['red']
        )
        self._btn_exit.pack(side='left', fill='x', expand=True)

        # Save dir path display
        self._savedir_lbl = tk.Label(
            self.root,
            text=truncate_path(self.save_dir),
            font=self.f_ip, bg=C['bg'], fg=C['text_faint'], anchor='w'
        )
        self._savedir_lbl.pack(fill='x', padx=PAD, pady=(0, 8))

        # ── Received log ──────────────────────────────────────────────────────
        log_hdr = tk.Frame(self.root, bg=C['bg'])
        log_hdr.pack(fill='x', padx=PAD, pady=(0, 4))
        tk.Label(
            log_hdr, text="TRANSFER LOG", font=self.f_section,
            bg=C['bg'], fg=C['text_faint']
        ).pack(side='left')

        log_frame = tk.Frame(
            self.root,
            bg=C['surface'],
            highlightthickness=1,
            highlightbackground=C['border']
        )
        log_frame.pack(fill='both', expand=True, padx=PAD, pady=(0, PAD))

        self._log = tk.Text(
            log_frame,
            bg=C['surface'], fg=C['text_dim'],
            font=self.f_mono,
            relief='flat', bd=0,
            state='disabled', wrap='none',
            insertbackground=C['amber'],
            selectbackground=C['amber_dim'],
        )
        self._log.pack(fill='both', expand=True, padx=8, pady=8)

        # Tag colours for sent / received / error lines
        self._log.tag_config('sent',     foreground=C['amber'])
        self._log.tag_config('received', foreground=C['green'])
        self._log.tag_config('err',      foreground=C['red'])
        self._log.tag_config('info',     foreground=C['text_dim'])
        self._log.tag_config('ts',       foreground=C['text_faint'])

    # ── Button factory ────────────────────────────────────────────────────────
    def _btn(self, parent, text, command, fg, bg, hover, outline=None):
        outline = outline or bg
        container = tk.Frame(
            parent, bg=outline,
            highlightthickness=1, highlightbackground=outline
        )
        b = tk.Button(
            container, text=text, font=self.f_btn,
            fg=fg, bg=bg,
            activeforeground=fg, activebackground=hover,
            relief='flat', bd=0, pady=10,
            cursor='hand2', command=command
        )
        b.pack(fill='both', expand=True)
        b.bind('<Enter>', lambda _: b.configure(bg=hover))
        b.bind('<Leave>', lambda _: b.configure(bg=bg))
        return container

    # ── Status control ────────────────────────────────────────────────────────
    def _set_status(self, ready: bool):
        if ready:
            self._dot.configure(fg=C['green'])
            self._status_lbl.configure(text="READY", fg=C['green'])
        else:
            self._dot.configure(fg=C['red'])
            self._status_lbl.configure(text="NOT READY", fg=C['red'])

    # ── Backend startup ───────────────────────────────────────────────────────
    def _start_backend(self):
        def _run():
            try:
                self.receiver.start()
                self.discovery.start()
                self.root.after(0, lambda: self._set_status(True))
                self.root.after(0, lambda: self._log_line(
                    f"listening on {self.local_ip}:{self.HTTP_PORT}", 'info'
                ))
            except Exception as e:
                self.root.after(0, lambda: self._set_status(False))
                self.root.after(0, lambda: self._log_line(f"startup error: {e}", 'err'))

        threading.Thread(target=_run, daemon=True).start()

    # ── Device discovery callback ─────────────────────────────────────────────
    def _on_device_found(self, device: Device):
        self.devices[device.device_id] = device
        self.root.after(0, self._redraw_devices)

    def _redraw_devices(self):
        for w in self._dev_list.winfo_children():
            w.destroy()

        count = len(self.devices)
        self._dev_count_lbl.configure(
            text=f"{count} found" if count else "0 found"
        )

        if count == 0:
            self._no_dev_lbl = tk.Label(
                self._dev_list, text="scanning the network\u2026",
                font=self.f_mono, bg=C['bg'], fg=C['text_faint']
            )
            self._no_dev_lbl.pack(pady=40)
            return

        for dev in self.devices.values():
            self._device_row(dev)

    def _device_row(self, device: Device):
        outer = tk.Frame(
            self._dev_list, bg=C['surface2'],
            highlightthickness=1, highlightbackground=C['border']
        )
        outer.pack(fill='x', pady=(0, 5))

        # Left amber accent bar
        tk.Frame(outer, bg=C['amber'], width=3).pack(side='left', fill='y')

        body = tk.Frame(outer, bg=C['surface2'], padx=10, pady=8)
        body.pack(side='left', fill='both', expand=True)

        tk.Label(
            body, text=device.name,
            font=self.f_device, bg=C['surface2'], fg=C['text'], anchor='w'
        ).pack(anchor='w')
        tk.Label(
            body, text=f"{device.address}:{device.http_port}",
            font=self.f_ip, bg=C['surface2'], fg=C['text_dim'], anchor='w'
        ).pack(anchor='w')

        # Inline send button
        side = tk.Frame(outer, bg=C['surface2'], padx=10)
        side.pack(side='right', fill='y')

        sb = tk.Button(
            side, text="SEND \u2192",
            font=('Consolas', 9, 'bold'),
            fg=C['amber'], bg=C['surface2'],
            activeforeground=C['bg'], activebackground=C['amber'],
            relief='flat', bd=0, cursor='hand2',
            command=lambda d=device: self._send_to(d)
        )
        sb.pack(expand=True)

        # Hover effect across the whole row
        def _on(_e=None):
            outer.configure(highlightbackground=C['amber'])
            sb.configure(fg=C['bg'], bg=C['amber'])

        def _off(_e=None):
            outer.configure(highlightbackground=C['border'])
            sb.configure(fg=C['amber'], bg=C['surface2'])

        for w in (outer, body, side, sb):
            w.bind('<Enter>', _on)
            w.bind('<Leave>', _off)

    # ── File received callback ────────────────────────────────────────────────
    def _on_file_received(self, info: dict):
        line = f"\u2190 {info['name']}  [{fmt_size(info['size'])}]  {info['path']}"
        self.root.after(0, lambda: self._log_line(line, 'received'))

    # ── Log helpers ───────────────────────────────────────────────────────────
    def _log_line(self, text: str, tag: str = 'info'):
        self._log.configure(state='normal')
        ts = datetime.now().strftime('%H:%M:%S')
        self._log.insert('end', f"[{ts}]  ", 'ts')
        self._log.insert('end', f"{text}\n", tag)
        self._log.see('end')
        self._log.configure(state='disabled')

    # ── Button: SEND FILE ─────────────────────────────────────────────────────
    def _on_send(self):
        if not self.devices:
            messagebox.showinfo(
                "No Devices Found",
                "No PyDrop devices detected on the network.\n"
                "Make sure the other device is running PyDrop.",
                parent=self.root
            )
            return

        paths = filedialog.askopenfilenames(
            title="Select files to send", parent=self.root
        )
        if not paths:
            return

        if len(self.devices) == 1:
            self._send_to(next(iter(self.devices.values())), paths)
        else:
            self._device_picker(paths)

    def _send_to(self, device: Device, paths=None):
        if paths is None:
            paths = filedialog.askopenfilenames(
                title="Select files to send", parent=self.root
            )
        if not paths:
            return

        def _worker():
            ok = err = 0
            total = len(paths)
            for p in paths:
                fname = os.path.basename(p)
                sz    = os.path.getsize(p)
                self.root.after(0, lambda f=fname, s=sz, d=device: self._log_line(
                    f"\u2192 {f}  [{fmt_size(s)}]  \u2192 {d.name} ({d.address})", 'sent'
                ))
                if send_file(device, p):
                    ok += 1
                else:
                    err += 1
                    self.root.after(0, lambda f=fname: self._log_line(
                        f"  FAILED: {f}", 'err'
                    ))

            self.root.after(0, lambda: self._log_line(
                f"  {ok}/{total} sent", 'info'
            ))
            if err:
                self.root.after(0, lambda: messagebox.showerror(
                    "Send Error", f"{err} file(s) failed. See transfer log.",
                    parent=self.root
                ))

        threading.Thread(target=_worker, daemon=True).start()

    def _device_picker(self, paths):
        win = tk.Toplevel(self.root)
        win.title("Select Device")
        win.configure(bg=C['bg'])
        win.resizable(False, False)
        win.grab_set()
        win.geometry("320x{}".format(60 + len(self.devices) * 72 + 50))

        tk.Frame(win, bg=C['amber'], height=2).pack(fill='x')
        tk.Label(
            win, text="SELECT TARGET DEVICE",
            font=self.f_section, bg=C['bg'], fg=C['text_faint']
        ).pack(padx=14, pady=(12, 8), anchor='w')

        for dev in self.devices.values():
            outer = tk.Frame(
                win, bg=C['surface2'],
                highlightthickness=1, highlightbackground=C['border']
            )
            outer.pack(fill='x', padx=14, pady=(0, 6))

            tk.Frame(outer, bg=C['amber'], width=3).pack(side='left', fill='y')

            inner = tk.Frame(outer, bg=C['surface2'], padx=10, pady=10)
            inner.pack(side='left', fill='both', expand=True)

            tk.Label(
                inner, text=dev.name,
                font=self.f_device, bg=C['surface2'], fg=C['text']
            ).pack(anchor='w')
            tk.Label(
                inner, text=f"{dev.address}:{dev.http_port}",
                font=self.f_ip, bg=C['surface2'], fg=C['text_dim']
            ).pack(anchor='w')

            def _click(d=dev):
                win.destroy()
                self._send_to(d, paths)

            def _on(e, ro=outer, ri=inner):
                ro.configure(highlightbackground=C['amber'])
                ri.configure(bg=C['border_hi'])

            def _off(e, ro=outer, ri=inner):
                ro.configure(highlightbackground=C['border'])
                ri.configure(bg=C['surface2'])

            for w in (outer, inner):
                w.bind('<Button-1>', lambda e, fn=_click: fn())
                w.bind('<Enter>', _on)
                w.bind('<Leave>', _off)

        tk.Button(
            win, text="CANCEL", font=self.f_btn,
            fg=C['text_faint'], bg=C['bg'],
            activeforeground=C['text'], activebackground=C['surface'],
            relief='flat', bd=0, pady=8, cursor='hand2',
            command=win.destroy
        ).pack(fill='x', padx=14, pady=(2, 12))

    # ── Button: SAVE DIR ──────────────────────────────────────────────────────
    def _on_choose_dir(self):
        chosen = filedialog.askdirectory(
            title="Choose folder for received files",
            initialdir=self.save_dir, parent=self.root
        )
        if chosen:
            self.save_dir = chosen
            self._savedir_lbl.configure(text=truncate_path(chosen))
            self._log_line(f"save dir \u2192 {chosen}", 'info')

    # ── Button: EXIT ──────────────────────────────────────────────────────────
    def _on_exit(self):
        self.discovery.stop()
        self.receiver.stop()
        self.root.destroy()


# ─────────────────────────────────────────────────────────────────────────────
#  Entry point
# ─────────────────────────────────────────────────────────────────────────────
def main():
    PyDropApp()


if __name__ == "__main__":
    main()
