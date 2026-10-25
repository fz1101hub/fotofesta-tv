"""Server DLNA minimo per le schermate nel simulatore e nell'emulatore: una cartella "Festa di
compleanno" con le foto (e i video .mp4) di una cartella, e una cartella "Musica" che va saltata.

Uso: python3 scripts/fake_dlna.py <indirizzo> <cartella> [indirizzo pubblicato]
Risponde sulla porta 8200 come MiniDLNA (/rootDesc.xml e Browse su /ctl/ContentDir). L'indirizzo
pubblicato è quello scritto negli indirizzi dei file, se chi li legge vede il server con un altro
indirizzo (l'emulatore Android raggiunge il computer che lo ospita con 10.0.2.2).
"""
import html
import socketserver
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, unquote

HOST = sys.argv[1]
FOLDER = Path(sys.argv[2])
PUBLISHED = sys.argv[3] if len(sys.argv) > 3 else HOST
BASE = f"http://{PUBLISHED}:8200"
KINDS = {".jpg": "image/jpeg", ".mp4": "video/mp4"}
MEDIA = sorted(path for path in FOLDER.iterdir() if path.suffix.lower() in KINDS)

DESCRIPTION = f"""<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
<deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
<friendlyName>NAS di casa</friendlyName><UDN>uuid:fotofesta-nas-di-prova</UDN>
<serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
<controlURL>/ctl/ContentDir</controlURL></service></serviceList></device></root>"""


def didl(content):
    return ('<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" '
            f'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">{content}</DIDL-Lite>')


def item(index, path):
    kind = KINDS[path.suffix.lower()]
    upnp_class = "object.item.videoItem" if kind.startswith("video/") else "object.item.imageItem.photo"
    return (f'<item id="p{index}" parentID="1"><dc:title>{html.escape(path.name)}</dc:title>'
            f'<upnp:class>{upnp_class}</upnp:class>'
            f'<res protocolInfo="http-get:*:{kind}:*">{BASE}/media/{quote(path.name)}</res></item>')


def browse(object_id):
    if object_id == "0":
        items = ['<container id="1" parentID="0"><dc:title>Festa di compleanno</dc:title>'
                 '<upnp:class>object.container.storageFolder</upnp:class></container>',
                 '<container id="2" parentID="0"><dc:title>Musica</dc:title>'
                 '<upnp:class>object.container.album.musicAlbum</upnp:class></container>']
    elif object_id == "1":
        items = [item(index, path) for index, path in enumerate(MEDIA)]
    else:
        items = []
    body = html.escape(didl("".join(items)), quote=False)
    return ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>'
            '<u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">'
            f'<Result>{body}</Result><NumberReturned>{len(items)}</NumberReturned>'
            f'<TotalMatches>{len(items)}</TotalMatches><UpdateID>1</UpdateID>'
            '</u:BrowseResponse></s:Body></s:Envelope>')


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        print(self.address_string(), format % args, flush=True)

    def reply(self, status, body, kind, headers=()):
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        for name, value in headers:
            self.send_header(name, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_GET(self):
        path = FOLDER / Path(unquote(self.path)).name
        if self.path == "/rootDesc.xml":
            self.reply(200, DESCRIPTION.encode(), "text/xml")
        elif self.path.startswith("/media/") and path in MEDIA:
            self.media(path)
        else:
            self.reply(404, b"", "text/plain")

    do_HEAD = do_GET

    def media(self, path):
        """File intero o, per i lettori video, solo la parte chiesta con Range: bytes=inizio-fine."""
        data = path.read_bytes()
        kind = KINDS[path.suffix.lower()]
        wanted = self.headers.get("Range", "")
        if wanted.startswith("bytes=") and "," not in wanted:
            first, _, last = wanted[6:].partition("-")
            start = int(first) if first else max(0, len(data) - int(last or 0))
            end = min(int(last), len(data) - 1) if first and last else len(data) - 1
            if start >= len(data) or start > end:
                self.reply(416, b"", "text/plain", [("Content-Range", f"bytes */{len(data)}")])
                return
            self.reply(206, data[start:end + 1], kind,
                       [("Content-Range", f"bytes {start}-{end}/{len(data)}"), ("Accept-Ranges", "bytes")])
        else:
            self.reply(200, data, kind, [("Accept-Ranges", "bytes")])

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        request = self.rfile.read(length).decode("utf-8", "replace")
        object_id = request.split("<ObjectID>")[1].split("</ObjectID>")[0] if "<ObjectID>" in request else "0"
        print("Browse", object_id, flush=True)
        self.reply(200, browse(object_id).encode(), 'text/xml; charset="utf-8"')


class Server(ThreadingHTTPServer):
    def server_bind(self):
        # HTTPServer cerca il nome del computer con il DNS inverso, che sul runner può bloccarsi
        # prima che il server si metta in ascolto.
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]


server = Server((HOST, 8200), Handler)
print("Server DLNA finto pronto su", HOST, "con", len(MEDIA), "file, pubblicato come", PUBLISHED, flush=True)
server.serve_forever()
