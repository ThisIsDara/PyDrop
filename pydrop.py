import asyncio
import json
import os
import socket
import threading
import uuid
import mimetypes
from pathlib import Path
from datetime import datetime

import qrcode
from PIL import Image
import io
from websockets.asyncio.server import serve
from aiohttp import web
import customtkinter as ctk
from tkinter import filedialog, messagebox
import urllib.request


COLORS = {
    'bg': '#0a0a0f',
    'panel': '#12121a',
    'accent': '#00d4aa',
    'secondary': '#ff6b35',
    'danger': '#ff3366',
    'text': '#ffffff',
    'text_dim': '#6a6a7a',
    'border': '#2a2a3a',
}


class Device:
    def __init__(self, name: str, address: str, port: int, http_port: int, device_id: str):
        self.name = name
        self.address = address
        self.port = port
        self.http_port = http_port
        self.device_id = device_id
        self.last_seen = datetime.now()


class UDPDiscovery:
    """UDP-based device discovery (works without mDNS)"""
    DISCOVERY_PORT = 8766
    BROADCAST_PORT = 8767
    MESSAGE_ANNOUNCE = b"PYDROP_ANNOUNCE"
    MESSAGE_DISCOVER = b"PYDROP_DISCOVER"
    
    def __init__(self, device_id: str, device_name: str, http_port: int, on_device_found):
        self.device_id = device_id
        self.device_name = device_name
        self.http_port = http_port
        self.on_device_found = on_device_found
        self.running = False
        self.sock = None
        
    def start(self):
        self.running = True
        threading.Thread(target=self._listen_loop, daemon=True).start()
        threading.Thread(target=self._broadcast_loop, daemon=True).start()
        
    def _listen_loop(self):
        try:
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            self.sock.settimeout(5)
            self.sock.bind(('', self.DISCOVERY_PORT))
            
            while self.running:
                try:
                    data, addr = self.sock.recvfrom(1024)
                    local_ip = self._get_local_ip()
                    if addr[0] != local_ip:
                        self._handle_message(data, addr[0])
                except socket.timeout:
                    continue
                except:
                    break
        except Exception as e:
            print(f"UDP listen error: {e}")
            
    def _broadcast_loop(self):
        while self.running:
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                msg = f"PYDROP_ANNOUNCE|{self.device_id}|{self.device_name}|{self.http_port}"
                sock.sendto(msg.encode(), ('255.255.255.255', self.BROADCAST_PORT))
                sock.close()
            except:
                pass
            threading.Event().wait(5)
            
    def _handle_message(self, data: bytes, addr: str):
        try:
            msg = data.decode()
            if msg.startswith("PYDROP_ANNOUNCE"):
                parts = msg.split("|")
                if len(parts) >= 4:
                    remote_id = parts[1]
                    if remote_id != self.device_id:
                        device = Device(parts[2], addr, 8765, int(parts[3]), remote_id)
                        self.on_device_found(device)
        except:
            pass
            
    def _get_local_ip(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(('10.255.255.255', 1))
            return s.getsockname()[0]
        except:
            return '127.0.0.1'
            
    def stop(self):
        self.running = False
        if self.sock:
            self.sock.close()


class PyDropServer:
    def __init__(self, device_name: str = None):
        self.device_name = device_name or socket.gethostname()
        self.device_id = uuid.uuid4().hex[:12]
        self.port = 8765
        self.http_port = 8080
        self.discovery_port = 8766
        
        self.devices: dict[str, Device] = {}
        self.received_files: dict[str, dict] = {}
        self.sent_files: dict[str, dict] = {}
        
        self.running = False
        self.udp_discovery = None
        
        self.gui_callback = None
        
    def set_gui_callback(self, callback):
        self.gui_callback = callback
        
    def get_local_ip(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(('10.255.255.255', 1))
            ip = s.getsockname()[0]
        except Exception:
            ip = '127.0.0.1'
        finally:
            s.close()
        return ip
    
    def start(self):
        self.running = True
        # Start UDP discovery (faster than mDNS)
        self.udp_discovery = UDPDiscovery(
            self.device_id, 
            self.device_name, 
            self.http_port,
            self._on_device_found
        )
        self.udp_discovery.start()
        threading.Thread(target=self._start_http_server, daemon=True).start()
        threading.Thread(target=self._run_ws_loop, daemon=True).start()
        
    def _on_device_found(self, device: Device):
        if device.device_id not in self.devices:
            self.devices[device.device_id] = device
            if self.gui_callback:
                self.gui_callback('device_found', {
                    'id': device.device_id,
                    'name': device.name,
                    'address': device.address,
                    'http_port': device.http_port
                })
        
    def _run_ws_loop(self):
        asyncio.run(self._start_websocket())
        
    def _start_http_server(self):
        asyncio.run(self._run_http_server())
        
    async def _run_http_server(self):
        app = web.Application()
        app.router.add_get('/', self.handle_index)
        app.router.add_get('/api/files', self.handle_list_files)
        app.router.add_get('/api/download', self.handle_download)
        app.router.add_post('/api/upload', self.handle_upload)
        app.router.add_get('/api/qr', self.handle_qr)
        app.router.add_get('/api/thumb', self.handle_thumb)
        app.router.add_get('/api/info', self.handle_info)
        
        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, '0.0.0.0', self.http_port)
        await site.start()
        print(f"HTTP server started on port {self.http_port}")
        
        while self.running:
            await asyncio.sleep(1)
            
    async def handle_index(self, request):
        index_path = Path(__file__).parent / 'public' / 'index.html'
        if index_path.exists():
            return web.FileResponse(index_path)
        return web.Response(text='<!html><html><body><h1>PyDrop</h1></body></html>', content_type='text/html')
        
    async def handle_info(self, request):
        return web.json_response({
            'deviceId': self.device_id,
            'deviceName': self.device_name,
            'ip': self.get_local_ip(),
            'httpPort': self.http_port
        })
        
    async def handle_list_files(self, request):
        files = []
        for fid, info in self.received_files.items():
            files.append({
                'id': fid,
                'name': info['name'],
                'size': info['size'],
                'time': info['time'],
                'direction': 'received'
            })
        for fid, info in self.sent_files.items():
            files.append({
                'id': fid,
                'name': info['name'],
                'size': info['size'],
                'time': info['time'],
                'direction': 'sent'
            })
        return web.json_response({'files': sorted(files, key=lambda x: x['time'], reverse=True)})
        
    async def handle_download(self, request):
        file_id = request.query.get('id')
        if file_id in self.received_files:
            info = self.received_files[file_id]
            filepath = Path(info['path'])
            if filepath.exists():
                return web.FileResponse(filepath, headers={'Content-Disposition': f'attachment; filename="{info["name"]}"'})
        return web.Response(text='Not found', status=404)
        
    async def handle_upload(self, request):
        reader = await request.multipart()
        field = await reader.next()
        if field:
            filename = field.filename
            file_id = uuid.uuid4().hex[:8]
            save_dir = Path(__file__).parent / 'received'
            save_dir.mkdir(exist_ok=True)
            filepath = save_dir / f"{file_id}_{filename}"
            
            size = 0
            with open(filepath, 'wb') as f:
                while True:
                    chunk = await field.read_chunk(8192)
                    if not chunk:
                        break
                    f.write(chunk)
                    size += len(chunk)
            
            self.received_files[file_id] = {
                'name': filename,
                'size': size,
                'time': datetime.now().isoformat(),
                'path': str(filepath)
            }
            
            if self.gui_callback:
                self.gui_callback('file_received', {
                    'name': filename,
                    'size': size,
                    'id': file_id
                })
            
            return web.json_response({'success': True, 'fileId': file_id})
        return web.Response(text='No file', status=400)
        
    async def handle_qr(self, request):
        local_ip = self.get_local_ip()
        qr_data = f"pydrop://{local_ip}:{self.http_port}/{self.device_id}"
        
        qr = qrcode.QRCode(box_size=10, border=2)
        qr.add_data(qr_data)
        qr.make(fit=True)
        img = qr.make_image(fill_color='#00d4aa', back_color='#0a0a0f')
        
        buffer = io.BytesIO()
        img.save(buffer, 'PNG')
        buffer.seek(0)
        
        return web.Response(body=buffer.getvalue(), content_type='image/png')
        
    async def handle_thumb(self, request):
        file_id = request.query.get('id')
        if file_id in self.received_files:
            info = self.received_files[file_id]
            filepath = Path(info['path'])
            if filepath.exists():
                mime, _ = mimetypes.guess_type(str(filepath))
                if mime and mime.startswith('image/'):
                    try:
                        img = Image.open(filepath)
                        img.thumbnail((200, 200))
                        buffer = io.BytesIO()
                        img.save(buffer, 'PNG')
                        return web.Response(body=buffer.getvalue(), content_type='image/png')
                    except:
                        pass
        return web.Response(text='', status=404)
        
    async def _start_websocket(self):
        async def handler(ws):
            try:
                async for msg in ws:
                    if isinstance(msg, str):
                        data = json.loads(msg)
                        await self._handle_websocket_message(ws, data)
            except Exception as e:
                print(f"WebSocket error: {e}")
                
        self.websocket_server = await serve(handler, "0.0.0.0", self.port)
        print(f"WebSocket server started on port {self.port}")
        
        while self.running:
            await asyncio.sleep(1)
            
    async def _handle_websocket_message(self, ws, data):
        msg_type = data.get('type')
        
        if msg_type == 'hello':
            await ws.send(json.dumps({
                'type': 'hello',
                'deviceId': self.device_id,
                'deviceName': self.device_name
            }))
            
        elif msg_type == 'announce':
            device = Device(
                data.get('deviceName', 'Unknown'),
                data.get('address', ''),
                data.get('port', 0),
                data.get('httpPort', 8080),
                data.get('deviceId', '')
            )
            self.devices[device.device_id] = device
            
            if self.gui_callback:
                self.gui_callback('device_found', {
                    'id': device.device_id,
                    'name': device.name,
                    'address': device.address,
                    'http_port': device.http_port
                })
                
        elif msg_type == 'file_offer':
            if self.gui_callback:
                self.gui_callback('file_offer', data)
                
    def send_file_to_device(self, device: Device, file_path: str):
        try:
            with open(file_path, 'rb') as f:
                files = {'file': f}
                req = urllib.request.Request(
                    f"http://{device.address}:{device.http_port}/api/upload",
                    data=f,
                    method='POST'
                )
                # Use multipart form data
                import multipart
                
            file_id = uuid.uuid4().hex[:8]
            filename = os.path.basename(file_path)
            file_size = os.path.getsize(file_path)
            
            self.sent_files[file_id] = {
                'name': filename,
                'size': file_size,
                'time': datetime.now().isoformat(),
                'to': device.name
            }
            
            if self.gui_callback:
                self.gui_callback('file_sent', {
                    'name': filename,
                    'size': file_size,
                    'to': device.name
                })
                
            return True
        except Exception as e:
            print(f"Failed to send file: {e}")
            return False
            
    def stop(self):
        self.running = False
        if self.udp_discovery:
            self.udp_discovery.stop()


class PyDropGUI:
    def __init__(self):
        ctk.set_appearance_mode("dark")
        ctk.set_default_color_theme("dark-blue")
        
        self.server = PyDropServer()
        self.server.set_gui_callback(self._on_server_event)
        
        self.root = ctk.CTk()
        self.root.title("⬡ PyDrop")
        self.root.geometry("560x600")
        self.root.resizable(False, False)
        
        self._create_ui()
        
        threading.Thread(target=self.server.start, daemon=True).start()
        
        self.root.mainloop()
        
    def _create_ui(self):
        # Main container
        main_frame = ctk.CTkFrame(self.root, fg_color=COLORS['bg'])
        main_frame.pack(fill='both', expand=True, padx=10, pady=10)
        
        # Header
        header = ctk.CTkFrame(main_frame, fg_color=COLORS['panel'], corner_radius=12)
        header.pack(fill='x', pady=(0, 10))
        
        title = ctk.CTkLabel(header, text="⬡ PyDrop", font=ctk.CTkFont(size=24, weight="bold"),
                            text_color=COLORS['accent'])
        title.pack(side='left', padx=20, pady=15)
        
        self.status_indicator = ctk.CTkLabel(header, text="●", font=ctk.CTkFont(size=16),
                                           text_color=COLORS['accent'])
        self.status_indicator.pack(side='right', padx=20)
        
        # Device Info
        info_frame = ctk.CTkFrame(main_frame, fg_color=COLORS['panel'], corner_radius=12)
        info_frame.pack(fill='x', pady=(0, 10))
        
        local_ip = self.server.get_local_ip()
        self.ip_label = ctk.CTkLabel(info_frame, text=f"IP: {local_ip}:{self.server.http_port}",
                                    font=ctk.CTkFont(size=13, family="Consolas"),
                                    text_color=COLORS['text'])
        self.ip_label.pack(anchor='w', padx=20, pady=(12, 5))
        
        self.id_label = ctk.CTkLabel(info_frame, text=f"ID: {self.server.device_id}",
                                    font=ctk.CTkFont(size=11, family="Consolas"),
                                    text_color=COLORS['text_dim'])
        self.id_label.pack(anchor='w', padx=20, pady=(0, 8))
        
        btn_row = ctk.CTkFrame(info_frame, fg_color='transparent')
        btn_row.pack(fill='x', padx=15, pady=(0, 12))
        
        qr_btn = ctk.CTkButton(btn_row, text="📱 QR Code", fg_color=COLORS['bg'],
                              hover_color=COLORS['accent'], command=self._show_qr)
        qr_btn.pack(side='left', padx=5)
        
        web_btn = ctk.CTkButton(btn_row, text="🌐 Web UI", fg_color=COLORS['secondary'],
                               hover_color='#ff8555', command=self._open_web_ui)
        web_btn.pack(side='left', padx=5)
        
        refresh_btn = ctk.CTkButton(btn_row, text="🔄 Refresh", fg_color=COLORS['bg'],
                                   hover_color=COLORS['accent'], command=self._refresh_devices)
        refresh_btn.pack(side='left', padx=5)
        
        # Devices Section
        devices_label = ctk.CTkLabel(main_frame, text="NEARBY DEVICES", font=ctk.CTkFont(size=11, weight="bold"),
                                   text_color=COLORS['text_dim'])
        devices_label.pack(anchor='w', padx=5, pady=(5, 8))
        
        self.devices_frame = ctk.CTkScrollableFrame(main_frame, fg_color='transparent',
                                                     height=120, scrollbar_button_color=COLORS['panel'])
        self.devices_frame.pack(fill='x', pady=(0, 10))
        
        # No devices placeholder
        self.no_devices_label = ctk.CTkLabel(self.devices_frame, text="🔍 Scanning for devices...",
                                            text_color=COLORS['text_dim'])
        self.no_devices_label.pack(pady=20)
        
        # Received Files Section
        files_label = ctk.CTkLabel(main_frame, text="FILES", font=ctk.CTkFont(size=11, weight="bold"),
                                  text_color=COLORS['text_dim'])
        files_label.pack(anchor='w', padx=5, pady=(5, 8))
        
        self.files_frame = ctk.CTkScrollableFrame(main_frame, fg_color='transparent',
                                                  height=180, scrollbar_button_color=COLORS['panel'])
        self.files_frame.pack(fill='both', expand=True, pady=(0, 10))
        
        self.no_files_label = ctk.CTkLabel(self.files_frame, text="No files received yet",
                                          text_color=COLORS['text_dim'])
        self.no_files_label.pack(pady=20)
        
        # Bottom Buttons
        btn_frame = ctk.CTkFrame(main_frame, fg_color='transparent')
        btn_frame.pack(fill='x', pady=(0, 0))
        
        send_btn = ctk.CTkButton(btn_frame, text="📤 SEND FILES", fg_color=COLORS['accent'],
                                 hover_color='#00b894', height=40,
                                 font=ctk.CTkFont(size=14, weight="bold"),
                                 command=self._send_files)
        send_btn.pack(side='left', fill='x', expand=True, padx=(0, 5))
        
        receive_btn = ctk.CTkButton(btn_frame, text="📥 RECEIVE", fg_color=COLORS['panel'],
                                    border_color=COLORS['accent'], border_width=1,
                                    hover_color=COLORS['accent'], height=40,
                                    font=ctk.CTkFont(size=14, weight="bold"),
                                    command=self._open_web_ui)
        receive_btn.pack(side='right', fill='x', expand=True, padx=(5, 0))
        
    def _on_server_event(self, event_type: str, data: dict):
        self.root.after(0, lambda: self._handle_event(event_type, data))
        
    def _handle_event(self, event_type: str, data: dict):
        if event_type == 'device_found':
            self._add_device(data)
        elif event_type == 'file_received':
            self._add_received_file(data)
        elif event_type == 'file_sent':
            self._add_sent_file(data)
            
    def _add_device(self, data: dict):
        if hasattr(self, 'no_devices_label'):
            self.no_devices_label.destroy()
            
        for widget in self.devices_frame.winfo_children():
            if getattr(widget, 'device_id', None) == data['id']:
                return
                
        device_frame = ctk.CTkFrame(self.devices_frame, fg_color=COLORS['panel'], corner_radius=8)
        device_frame.pack(fill='x', pady=3, padx=5)
        device_frame.device_id = data['id']
        
        status = ctk.CTkLabel(device_frame, text="●", font=ctk.CTkFont(size=10),
                             text_color=COLORS['accent'])
        status.pack(side='left', padx=(12, 5))
        
        name_label = ctk.CTkLabel(device_frame, text=data['name'],
                                 font=ctk.CTkFont(size=13),
                                 text_color=COLORS['text'])
        name_label.pack(side='left', padx=5)
        
        ip_label = ctk.CTkLabel(device_frame, text=data['address'],
                               font=ctk.CTkFont(size=10, family="Consolas"),
                               text_color=COLORS['text_dim'])
        ip_label.pack(side='left', padx=5)
        
    def _add_received_file(self, data: dict):
        if hasattr(self, 'no_files_label'):
            self.no_files_label.destroy()
            
        file_frame = ctk.CTkFrame(self.files_frame, fg_color=COLORS['panel'], corner_radius=8)
        file_frame.pack(fill='x', pady=3, padx=5)
        
        icon_label = ctk.CTkLabel(file_frame, text="📄", font=ctk.CTkFont(size=16))
        icon_label.pack(side='left', padx=(12, 8))
        
        info_frame = ctk.CTkFrame(file_frame, fg_color='transparent')
        info_frame.pack(side='left', fill='x', expand=True, padx=5)
        
        name_label = ctk.CTkLabel(info_frame, text=data['name'],
                                 font=ctk.CTkFont(size=12),
                                 text_color=COLORS['text'])
        name_label.pack(anchor='w')
        
        size_label = ctk.CTkLabel(info_frame, text=self._format_size(data['size']),
                                 font=ctk.CTkFont(size=10, family="Consolas"),
                                 text_color=COLORS['text_dim'])
        size_label.pack(anchor='w')
        
    def _add_sent_file(self, data: dict):
        if hasattr(self, 'no_files_label'):
            self.no_files_label.destroy()
            
        file_frame = ctk.CTkFrame(self.files_frame, fg_color=COLORS['panel'], corner_radius=8)
        file_frame.pack(fill='x', pady=3, padx=5)
        
        icon_label = ctk.CTkLabel(file_frame, text="📤", font=ctk.CTkFont(size=16))
        icon_label.pack(side='left', padx=(12, 8))
        
        info_frame = ctk.CTkFrame(file_frame, fg_color='transparent')
        info_frame.pack(side='left', fill='x', expand=True, padx=5)
        
        name_label = ctk.CTkLabel(info_frame, text=f"→ {data['name']}",
                                 font=ctk.CTkFont(size=12),
                                 text_color=COLORS['secondary'])
        name_label.pack(anchor='w')
        
        to_label = ctk.CTkLabel(info_frame, text=f"To: {data['to']}",
                               font=ctk.CTkFont(size=10, family="Consolas"),
                               text_color=COLORS['text_dim'])
        to_label.pack(anchor='w')
        
    def _show_qr(self):
        qr_window = ctk.CTkToplevel(self.root)
        qr_window.title("Scan to Connect")
        qr_window.geometry("320x400")
        qr_window.resizable(False, False)
        
        ctk.CTkLabel(qr_window, text="Scan to Connect", font=ctk.CTkFont(size=18, weight="bold"),
                    text_color=COLORS['accent']).pack(pady=20)
        
        qr_canvas = ctk.CTkCanvas(qr_window, width=200, height=200, bg=COLORS['panel'], highlightthickness=0)
        qr_canvas.pack(pady=10)
        
        try:
            url = f"http://localhost:{self.server.http_port}/api/qr"
            with urllib.request.urlopen(url, timeout=5) as response:
                img_data = response.read()
                from PIL import Image, ImageTk
                img = Image.open(io.BytesIO(img_data))
                self.qr_image = ImageTk.PhotoImage(img)
                qr_canvas.create_image(100, 100, image=self.qr_image)
        except:
            qr_canvas.create_text(100, 100, text="QR Unavailable", fill=COLORS['text_dim'])
            
        ctk.CTkLabel(qr_window, text=f"IP: {self.server.get_local_ip()}",
                    font=ctk.CTkFont(size=12, family="Consolas"),
                    text_color=COLORS['text']).pack(pady=10)
        
        ctk.CTkLabel(qr_window, text=f"Device ID: {self.server.device_id}",
                    font=ctk.CTkFont(size=10, family="Consolas"),
                    text_color=COLORS['text_dim']).pack()
        
        ctk.CTkButton(qr_window, text="Close", fg_color=COLORS['panel'],
                     command=qr_window.destroy).pack(pady=20)
        
    def _send_files(self):
        files = filedialog.askopenfilenames(title="Select files to send")
        if not files:
            return
            
        if not self.server.devices:
            messagebox.showinfo("No Devices", "No devices found nearby.\nMake sure other devices are running PyDrop.")
            return
            
        select_window = ctk.CTkToplevel(self.root)
        select_window.title("Select Device")
        select_window.geometry("350x450")
        
        ctk.CTkLabel(select_window, text="Select device to send to:", font=ctk.CTkFont(size=14, weight="bold"),
                    text_color=COLORS['text']).pack(pady=15)
        
        scroll = ctk.CTkScrollableFrame(select_window, fg_color='transparent')
        scroll.pack(fill='both', expand=True, padx=20, pady=10)
        
        for dev_id, device in self.server.devices.items():
            btn = ctk.CTkButton(scroll, text=f"{device.name}\n{device.address}",
                              fg_color=COLORS['panel'], border_width=1, border_color=COLORS['accent'],
                              command=lambda d=device: self._do_send(d, files, select_window))
            btn.pack(fill='x', pady=5)
            
    def _do_send(self, device: Device, files: tuple, window):
        success_count = 0
        for f in files:
            if self._upload_file(device, f):
                success_count += 1
                
        messagebox.showinfo("Sent", f"Sent {success_count}/{len(files)} file(s) to {device.name}")
        window.destroy()
        
    def _upload_file(self, device: Device, file_path: str) -> bool:
        try:
            import urllib.request
            import mimetypes
            
            filename = os.path.basename(file_path)
            mime_type = mimetypes.guess_type(file_path)[0] or 'application/octet-stream'
            
            boundary = '----WebKitFormBoundary' + uuid.uuid4().hex
            
            with open(file_path, 'rb') as f:
                file_data = f.read()
            
            body = f'--{boundary}\r\n'.encode()
            body += f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode()
            body += f'Content-Type: {mime_type}\r\n\r\n'.encode()
            body += file_data
            body += f'\r\n--{boundary}--\r\n'.encode()
            
            req = urllib.request.Request(
                f"http://{device.address}:{device.http_port}/api/upload",
                data=body,
                method='POST',
                headers={
                    'Content-Type': f'multipart/form-data; boundary={boundary}',
                    'Content-Length': str(len(body))
                }
            )
            
            urllib.request.urlopen(req, timeout=60)
            return True
        except Exception as e:
            print(f"Upload failed: {e}")
            return False
        
    def _open_web_ui(self):
        import webbrowser
        webbrowser.open(f"http://localhost:{self.server.http_port}")
        
    def _refresh_devices(self):
        self.server.devices.clear()
        for widget in self.devices_frame.winfo_children():
            widget.destroy()
        self.no_devices_label = ctk.CTkLabel(self.devices_frame, text="🔄 Rescanning...",
                                            text_color=COLORS['text_dim'])
        self.no_devices_label.pack(pady=20)
        
    def _format_size(self, size: int) -> str:
        for unit in ['B', 'KB', 'MB', 'GB']:
            if size < 1024:
                return f"{size:.1f} {unit}"
            size /= 1024
        return f"{size:.1f} TB"


def main():
    app = PyDropGUI()


if __name__ == "__main__":
    main()
