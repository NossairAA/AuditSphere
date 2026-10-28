"""Synthetic evidence documents for the demo dataset: PDF work papers and PNG application screens.

Standard library only, because the nightly demo reset runs in a bare python image. The PDF uses the standard
Helvetica fonts with WinAnsi encoding. The PNG screens are drawn from the glyph atlas of demo_font.py.

render(spec, context) returns (file bytes, media type). spec follows the 'evidence' entries of demo_dataset.py,
with date tokens already replaced. context gives the company, mission, work paper reference, author and date.
"""
import base64
import struct
import zlib

import demo_font

FOOTER = 'Données fictives de démonstration AuditSphere'
# Characters outside WinAnsi that the dataset may use, written with their closest WinAnsi form.
PDF_SUBSTITUTES = {'≤': '<=', '≥': '>=', '✓': 'Oui', '✗': 'Non', '▾': 'v', '▸': '>', ' ': ' ', ' ': ' '}


# ---------------------------------------------------------------------------------------------------- PDF

def _pdf_text(text):
    text = ''.join(PDF_SUBSTITUTES.get(c, c) for c in text)
    raw = text.encode('cp1252', 'replace')
    return raw.replace(b'\\', b'\\\\').replace(b'(', b'\\(').replace(b')', b'\\)')


def _width(text, size, bold=False):
    table = demo_font.HELVETICA_BOLD if bold else demo_font.HELVETICA
    total = 0
    for byte in ''.join(PDF_SUBSTITUTES.get(c, c) for c in text).encode('cp1252', 'replace'):
        total += table[byte - 32] if 32 <= byte <= 255 else 500
    return total * size / 1000


def _wrap(text, width, size, bold=False):
    lines = []
    for paragraph in str(text).split('\n'):
        current = ''
        for word in paragraph.split(' '):
            candidate = (current + ' ' + word) if current else word
            if _width(candidate, size, bold) <= width or not current:
                current = candidate
            else:
                lines.append(current)
                current = word
            # A single word wider than the column is cut, rare with real data.
            while _width(current, size, bold) > width and len(current) > 1:
                cut = len(current)
                while cut > 1 and _width(current[:cut], size, bold) > width:
                    cut -= 1
                lines.append(current[:cut])
                current = current[cut:]
        lines.append(current)
    return lines


class _Pdf:
    """Collects drawing operations per page, then writes the file once the page count is known."""

    def __init__(self, landscape, context):
        self.w, self.h = (842, 595) if landscape else (595, 842)
        self.margin = 42
        self.context = context
        self.pages = []
        self.new_page()

    def new_page(self):
        self.ops = []
        self.pages.append(self.ops)
        self.y = self.h - self.margin
        self._header()

    def rect(self, x, y, w, h, fill=None, stroke=None, line=0.5):
        if fill:
            self.ops.append(f'{fill[0]:.3f} {fill[1]:.3f} {fill[2]:.3f} rg {x:.2f} {y:.2f} {w:.2f} {h:.2f} re f')
        if stroke:
            self.ops.append(f'{line} w {stroke[0]:.3f} {stroke[1]:.3f} {stroke[2]:.3f} RG '
                            f'{x:.2f} {y:.2f} {w:.2f} {h:.2f} re S')

    def line(self, x1, y1, x2, y2, color=(0.75, 0.78, 0.82), width=0.5):
        self.ops.append(f'{width} w {color[0]:.3f} {color[1]:.3f} {color[2]:.3f} RG {x1:.2f} {y1:.2f} m {x2:.2f} {y2:.2f} l S')

    def text(self, x, y, text, size=9.5, bold=False, color=(0.13, 0.15, 0.18)):
        self.ops.append(f'BT {color[0]:.3f} {color[1]:.3f} {color[2]:.3f} rg /{"F2" if bold else "F1"} {size} Tf '
                        f'{x:.2f} {y:.2f} Td (' + _pdf_text(text).decode('latin-1') + ') Tj ET')

    def _header(self):
        c = self.context
        top = self.h - 30
        self.text(self.margin, top, c['company'].upper(), 8.5, True, (0.12, 0.23, 0.37))
        label = 'Diffusion interne · Audit interne'
        self.text(self.w - self.margin - _width(label, 7.5), top, label, 7.5, False, (0.45, 0.48, 0.52))
        self.line(self.margin, top - 6, self.w - self.margin, top - 6, (0.12, 0.23, 0.37), 0.8)
        self.y = top - 26

    def ensure(self, height):
        if self.y - height < self.margin + 24:
            self.new_page()
            return True
        return False

    def build(self):
        objects = []

        def add(body):
            objects.append(body)
            return len(objects)

        catalog = add(None)
        pages = add(None)
        regular = add(b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>')
        bold = add(b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>')
        info = add(b'<< /Producer (AuditSphere demo) /Title (' + _pdf_text(self.context.get('title', '')) + b') >>')
        kids = []
        total = len(self.pages)
        for number, ops in enumerate(self.pages, 1):
            footer = []
            muted = (0.45, 0.48, 0.52)
            left = f'{self.context["company"]} · {FOOTER}'
            right = f'{self.context["reference"]} · Page {number} / {total}'
            for x, label in ((self.margin, left), (self.w - self.margin - _width(right, 7), right)):
                footer.append(f'BT {muted[0]} {muted[1]} {muted[2]} rg /F1 7 Tf {x:.2f} 24 Td ('
                              + _pdf_text(label).decode('latin-1') + ') Tj ET')
            stream = zlib.compress('\n'.join(ops + footer).encode('latin-1'))
            content = add(b'<< /Length %d /Filter /FlateDecode >>\nstream\n' % len(stream) + stream + b'\nendstream')
            kids.append(add(f'<< /Type /Page /Parent {pages} 0 R /MediaBox [0 0 {self.w} {self.h}] '
                            f'/Resources << /Font << /F1 {regular} 0 R /F2 {bold} 0 R >> >> '
                            f'/Contents {content} 0 R >>'.encode()))
        objects[catalog - 1] = f'<< /Type /Catalog /Pages {pages} 0 R >>'.encode()
        objects[pages - 1] = (f'<< /Type /Pages /Count {total} /Kids ['
                              + ' '.join(f'{k} 0 R' for k in kids) + '] >>').encode()
        out = bytearray(b'%PDF-1.4\n%\xe2\xe3\xcf\xd3\n')
        offsets = []
        for number, body in enumerate(objects, 1):
            offsets.append(len(out))
            out += b'%d 0 obj\n' % number + body + b'\nendobj\n'
        xref = len(out)
        out += b'xref\n0 %d\n0000000000 65535 f \n' % (len(objects) + 1)
        out += b''.join(b'%010d 00000 n \n' % o for o in offsets)
        out += (b'trailer\n<< /Size %d /Root %d 0 R /Info %d 0 R >>\nstartxref\n%d\n%%%%EOF\n'
                % (len(objects) + 1, catalog, info, xref))
        return bytes(out)


def _title_block(pdf, spec):
    width = pdf.w - 2 * pdf.margin
    for line in _wrap(spec['title'], width, 15, True):
        pdf.text(pdf.margin, pdf.y, line, 15, True, (0.10, 0.13, 0.18))
        pdf.y -= 19
    if spec.get('subtitle'):
        for line in _wrap(spec['subtitle'], width, 8.5):
            pdf.text(pdf.margin, pdf.y, line, 8.5, False, (0.38, 0.42, 0.47))
            pdf.y -= 11
    c = pdf.context
    pdf.y -= 4
    pdf.rect(pdf.margin, pdf.y - 30, width, 34, fill=(0.95, 0.96, 0.98))
    cells = [('Mission', c['mission']), ('Feuille de travail', c['reference']),
             ('Préparé par', f"{c['author']}, le {c['date']}")]
    x = pdf.margin + 8
    for label, value in cells:
        share = width * (0.5 if label == 'Mission' else 0.25) - 12
        pdf.text(x, pdf.y - 8, label.upper(), 6.5, True, (0.45, 0.48, 0.52))
        shown = _wrap(value, share, 8.5)[0]
        if shown != value:
            while shown and _width(shown + '…', share, 8.5) > share:
                shown = shown[:-1]
            shown += '…'
        pdf.text(x, pdf.y - 21, shown, 8.5)
        x += share + 12
    pdf.y -= 50


def pdf_table(spec, context):
    columns = spec['columns']
    rows = [[str(v) for v in row] for row in spec['rows']]
    highlight = set(spec.get('highlight', []))
    size, pad = 8, 4
    natural = []
    for i, name in enumerate(columns):
        widest = max([_width(name, size, True)] + [_width(r[i], size) for r in rows if i < len(r)])
        natural.append(min(widest, 230) + 2 * pad)
    portrait = 595 - 84
    landscape = len(columns) >= 6 or sum(natural) > portrait * 1.15
    pdf = _Pdf(landscape, {**context, 'title': spec['title']})
    _title_block(pdf, spec)
    available = pdf.w - 2 * pdf.margin
    total = sum(natural)
    widths = [w * available / total for w in natural] if total > available else \
        [w + (available - total) / len(natural) for w in natural]

    def header():
        lines = [_wrap(name, widths[i] - 2 * pad, size, True) for i, name in enumerate(columns)]
        height = max(len(l) for l in lines) * 10 + 8
        pdf.rect(pdf.margin, pdf.y - height, available, height, fill=(0.12, 0.23, 0.37))
        x = pdf.margin
        for i, block in enumerate(lines):
            for n, line in enumerate(block):
                pdf.text(x + pad, pdf.y - 11 - n * 10, line, size, True, (1, 1, 1))
            x += widths[i]
        pdf.y -= height

    header()
    for index, row in enumerate(rows):
        lines = [_wrap(row[i] if i < len(row) else '', widths[i] - 2 * pad, size) for i in range(len(columns))]
        height = max(len(l) for l in lines) * 10 + 6
        if pdf.ensure(height):
            header()
        fill = (1.0, 0.93, 0.90) if index in highlight else ((0.97, 0.98, 0.99) if index % 2 else None)
        if fill:
            pdf.rect(pdf.margin, pdf.y - height, available, height, fill=fill)
        if index in highlight:
            pdf.rect(pdf.margin, pdf.y - height, 2.2, height, fill=(0.75, 0.22, 0.17))
        x = pdf.margin
        for i, block in enumerate(lines):
            for n, line in enumerate(block):
                pdf.text(x + pad, pdf.y - 10 - n * 10, line, size, False,
                         (0.55, 0.13, 0.10) if index in highlight else (0.13, 0.15, 0.18))
            x += widths[i]
        pdf.y -= height
        pdf.line(pdf.margin, pdf.y, pdf.margin + available, pdf.y, (0.86, 0.88, 0.91), 0.4)
    pdf.y -= 8
    count = f'{len(rows)} ligne{"s" if len(rows) > 1 else ""}'
    if highlight:
        count += f', dont {len(highlight)} en exception (surlignée{"s" if len(highlight) > 1 else ""})'
    pdf.text(pdf.margin, pdf.y, count, 7.5, False, (0.38, 0.42, 0.47))
    pdf.y -= 18
    if spec.get('notes'):
        pdf.ensure(30)
        pdf.text(pdf.margin, pdf.y, 'Notes de l’auditeur', 10, True, (0.12, 0.23, 0.37))
        pdf.y -= 14
        for note in spec['notes']:
            block = _wrap(note, available - 14, 9)
            pdf.ensure(len(block) * 12 + 4)
            pdf.text(pdf.margin + 2, pdf.y, '•', 9)
            for line in block:
                pdf.text(pdf.margin + 12, pdf.y, line, 9)
                pdf.y -= 12
            pdf.y -= 3
    return pdf.build()


def pdf_memo(spec, context):
    pdf = _Pdf(False, {**context, 'title': spec['title']})
    _title_block(pdf, spec)
    available = pdf.w - 2 * pdf.margin
    meta = spec.get('meta', [])
    if meta:
        key_width = max(_width(k, 8.5, True) for k, _ in meta) + 14
        for key, value in meta:
            block = _wrap(value, available - key_width, 9)
            pdf.ensure(len(block) * 12)
            pdf.text(pdf.margin, pdf.y, key, 8.5, True, (0.38, 0.42, 0.47))
            for line in block:
                pdf.text(pdf.margin + key_width, pdf.y, line, 9)
                pdf.y -= 12
            pdf.y -= 2
        pdf.y -= 4
        pdf.line(pdf.margin, pdf.y, pdf.margin + available, pdf.y)
        pdf.y -= 18
    for number, (heading, body) in enumerate(spec.get('sections', []), 1):
        pdf.ensure(40)
        pdf.text(pdf.margin, pdf.y, f'{number}. {heading}', 11, True, (0.12, 0.23, 0.37))
        pdf.y -= 16
        for line in _wrap(body, available, 9.5):
            pdf.ensure(13)
            if line:
                pdf.text(pdf.margin, pdf.y, line, 9.5)
            pdf.y -= 13
        pdf.y -= 8
    return pdf.build()


# ---------------------------------------------------------------------------------------------------- PNG

_GLYPHS = {}


def _font(name):
    if name not in _GLYPHS:
        data = zlib.decompress(base64.b64decode(''.join(demo_font.FONTS[name]['data'])))
        glyphs, offset = {}, 0
        while offset < len(data):
            code, advance, x0, y0, w, h = struct.unpack_from('<HHbbBB', data, offset)
            offset += 8
            glyphs[chr(code)] = (advance / 16, x0, y0, w, h, data[offset:offset + w * h])
            offset += w * h
        _GLYPHS[name] = glyphs
    return _GLYPHS[name]


def _rgb(value):
    return bytes(int(value[i:i + 2], 16) for i in (1, 3, 5))


class _Canvas:
    def __init__(self, width, height, background):
        self.w, self.h = width, height
        self.pixels = bytearray(_rgb(background) * width * height)

    def rect(self, x, y, w, h, color):
        x, y = max(0, x), max(0, y)
        w, h = min(w, self.w - x), min(h, self.h - y)
        if w <= 0 or h <= 0:
            return
        row = _rgb(color) * w
        for line in range(y, y + h):
            start = (line * self.w + x) * 3
            self.pixels[start:start + w * 3] = row

    def frame(self, x, y, w, h, color):
        self.rect(x, y, w, 1, color)
        self.rect(x, y + h - 1, w, 1, color)
        self.rect(x, y, 1, h, color)
        self.rect(x + w - 1, y, 1, h, color)

    def measure(self, text, font='body'):
        glyphs = _font(font)
        return sum(glyphs.get(c, glyphs['?'])[0] for c in text)

    def fit(self, text, width, font='body'):
        if self.measure(text, font) <= width:
            return text
        while text and self.measure(text + '…', font) > width:
            text = text[:-1]
        return text.rstrip() + '…'

    def text(self, x, baseline, text, color, font='body'):
        glyphs = _font(font)
        r, g, b = _rgb(color)
        pen = float(x)
        for char in text:
            advance, x0, y0, w, h, alpha = glyphs.get(char, glyphs['?'])
            left, top = int(round(pen)) + x0, baseline + y0
            for j in range(h):
                py = top + j
                if not 0 <= py < self.h:
                    continue
                for i in range(w):
                    a = alpha[j * w + i]
                    px = left + i
                    if a == 0 or not 0 <= px < self.w:
                        continue
                    o = (py * self.w + px) * 3
                    p = self.pixels
                    if a == 15:
                        p[o], p[o + 1], p[o + 2] = r, g, b
                    else:
                        p[o] = (p[o] * (15 - a) + r * a) // 15
                        p[o + 1] = (p[o + 1] * (15 - a) + g * a) // 15
                        p[o + 2] = (p[o + 2] * (15 - a) + b * a) // 15
            pen += advance

    def png(self):
        raw = bytearray()
        stride = self.w * 3
        for y in range(self.h):
            raw += b'\x00' + self.pixels[y * stride:(y + 1) * stride]

        def chunk(kind, body):
            return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', zlib.crc32(kind + body))

        return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', self.w, self.h, 8, 2, 0, 0, 0))
                + chunk(b'IDAT', zlib.compress(bytes(raw), 6)) + chunk(b'IEND', b''))


def png_screen(spec, context):
    columns = spec['columns']
    rows = [[str(v) for v in row] for row in spec['rows']]
    highlight = set(spec.get('highlight', []))
    width = 1280
    shell, bar, tool, head, row_h, status = 44, 52, 46, 34, 32, 30
    height = max(420, shell + bar + tool + 24 + head + row_h * len(rows) + 40 + status)
    c = _Canvas(width, height, '#F5F6F7')
    ink, muted, line = '#32363A', '#6A6D70', '#E5E5E5'
    # Application shell.
    c.rect(0, 0, width, shell, '#354A5F')
    c.text(20, 28, spec['app'], '#FFFFFF', 'bold')
    user = context['author']
    initials = ''.join(part[0] for part in user.split()[:2]).upper()
    c.rect(width - 52, 8, 28, 28, '#5B738B')
    c.text(width - 38 - int(c.measure(initials, 'small') / 2), 27, initials, '#FFFFFF', 'small')
    c.text(width - 64 - int(c.measure(user, 'small')), 27, user, '#D1DCE6', 'small')
    # Screen title.
    c.rect(0, shell, width, bar, '#FFFFFF')
    c.rect(0, shell + bar - 1, width, 1, line)
    c.text(24, shell + 34, c.fit(spec['screen'], width - 300, 'title'), ink, 'title')
    stamp = f"{context['company']} · {context['date']}"
    c.text(width - 24 - int(c.measure(stamp, 'small')), shell + 32, stamp, muted, 'small')
    # Toolbar.
    y = shell + bar + 10
    x = 24
    for label in ('Afficher', 'Filtrer', 'Exporter', 'Imprimer'):
        w = int(c.measure(label, 'small')) + 24
        c.rect(x, y, w, 28, '#FFFFFF')
        c.frame(x, y, w, 28, '#89919A')
        c.text(x + 12, y + 19, label, '#0A6ED1', 'small')
        x += w + 8
    c.rect(width - 304, y, 280, 28, '#FFFFFF')
    c.frame(width - 304, y, 280, 28, '#89919A')
    c.text(width - 294, y + 19, 'Rechercher', '#8C9196', 'small')
    count = f'{len(rows)} enregistrement{"s" if len(rows) > 1 else ""}'
    c.text(x + 12, y + 19, count, muted, 'small')
    # Table.
    top = shell + bar + tool + 14
    left, available = 24, width - 48
    natural = [max([c.measure(n, 'bold')] + [c.measure(r[i], 'body') for r in rows if i < len(r)]) + 24
               for i, n in enumerate(columns)]
    scale = min(1.0, available / sum(natural))
    widths = [n * scale for n in natural]
    widths[-1] += available - sum(widths)
    c.rect(left, top, available, head + row_h * len(rows), '#FFFFFF')
    c.rect(left, top, available, head, '#F2F2F2')
    x = left
    for i, name in enumerate(columns):
        c.text(int(x) + 12, top + 22, c.fit(name, widths[i] - 20, 'bold'), ink, 'bold')
        x += widths[i]
    c.rect(left, top + head - 1, available, 1, '#D9D9D9')
    for index, row in enumerate(rows):
        y = top + head + index * row_h
        if index in highlight:
            c.rect(left, y, available, row_h, '#FFF3CD')
            c.rect(left, y, 3, row_h, '#E9730C')
        x = left
        for i in range(len(columns)):
            value = row[i] if i < len(row) else ''
            c.text(int(x) + 12, y + 21, c.fit(value, widths[i] - 20), ink)
            x += widths[i]
        c.rect(left, y + row_h - 1, available, 1, line)
    c.frame(left, top, available, head + row_h * len(rows), '#D9D9D9')
    # Status bar.
    c.rect(0, height - status, width, status, '#EFF1F2')
    c.rect(0, height - status, width, 1, '#D9D9D9')
    info = f"{spec['app']} · Utilisateur {user} · {context['date']} · Lecture seule"
    c.text(24, height - 10, info, muted, 'small')
    mark = FOOTER
    c.text(width - 24 - int(c.measure(mark, 'small')), height - 10, mark, muted, 'small')
    return c.png()


def render(spec, context):
    kind = spec['type']
    if kind == 'table':
        return pdf_table(spec, context), 'application/pdf'
    if kind == 'memo':
        return pdf_memo(spec, context), 'application/pdf'
    if kind == 'screen':
        return png_screen(spec, context), 'image/png'
    raise ValueError(f'Type de justificatif inconnu : {kind}')
