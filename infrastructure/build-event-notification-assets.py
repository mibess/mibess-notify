#!/usr/bin/env python3
"""Build original typographic notification cards and their routing inventory.

No remote calls, credentials, uploads, or production mutations. Requires Pillow.
SVG and JPEG are rendered from the same primitives; no existing raster is edited.
Run from any directory: python3 infrastructure/build-event-notification-assets.py
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "assets/notification-images/v3"
WIDTH, HEIGHT, SCALE = 960, 320, 2

SOURCES = {
    "shopee": {"name": "Shopee", "channel": "Shopee", "accent": "#EE4D2D", "ink": "#8F2817", "tint": "#FFF0EA"},
    "mercado-livre": {"name": "Mercado Livre", "channel": "Mercado Livre", "accent": "#FFE600", "ink": "#253260", "tint": "#FFFBE0"},
    "artgian-site": {"name": "Artgian Site", "channel": "Artgian Studio", "accent": "#B79149", "ink": "#182B3D", "tint": "#F4F1E8"},
    "artgian-crm": {"name": "Artgian CRM", "channel": None, "accent": "#B35F47", "ink": "#68392D", "tint": "#F8ECE7"},
}


def condition(field, value, operator="EQUALS"):
    return {"field": "data." + field, "operator": operator, "value": value}


EVENTS = [
    ("novo-pedido", "Novo Pedido", "CRM_ORDER_IMPORTED", "box", []),
    ("pagamento-confirmado", "Pagamento Confirmado", "CRM_ORDER_PAID", "check", []),
    ("pagamento-recusado", "Pagamento Recusado", "CRM_ORDER_PAYMENT_ISSUE", "alert", [condition("paymentStatus", "rejected")]),
    ("pedido-cancelado", "Pedido Cancelado", "CRM_ORDER_PAYMENT_ISSUE", "cross", [condition("paymentStatus", "cancelled")]),
    ("pedido-reembolsado", "Pedido Reembolsado", "CRM_ORDER_PAYMENT_ISSUE", "return", [condition("paymentStatus", "refunded")]),
    ("pagamento-em-contestacao", "Pagamento em Contestação", "CRM_ORDER_PAYMENT_ISSUE", "alert", [condition("paymentStatus", "charged_back")]),
    ("pedido-enviado", "Pedido Enviado", "CRM_ORDER_FULFILLMENT_UPDATED", "truck", [condition("fulfillmentStatus", "shipped")]),
    ("pedido-saiu-para-entrega", "Pedido Saiu para Entrega", "CRM_ORDER_FULFILLMENT_UPDATED", "truck", [condition("fulfillmentStatus", "out_for_delivery")]),
    ("pedido-entregue", "Pedido Entregue", "CRM_ORDER_FULFILLMENT_UPDATED", "check", [condition("fulfillmentStatus", "delivered")]),
    ("revise-os-custos-do-pedido", "Revise os Custos do Pedido", "CRM_ORDER_COSTS_OUTDATED", "receipt", []),
    ("reconexao-necessaria", "Reconexão Necessária", "CRM_SYNC_FAILED", "link", [condition("code", "reauth_required")]),
    ("falha-na-sincronizacao", "Falha na Sincronização", "CRM_SYNC_FAILED", "alert", [condition("code", "reauth_required", "NOT_EQUALS")]),
    ("sincronizacao-normalizada", "Sincronização Normalizada", "CRM_SYNC_RECOVERED", "check", []),
]


def inventory():
    artworks, recipes = {}, []
    for source_id in ("shopee", "mercado-livre", "artgian-site"):
        source = SOURCES[source_id]
        for slug, label, event_type, icon, conditions in EVENTS:
            asset = f"{source_id}-{slug}"
            title = f"{source['name']} - {label}"
            artworks[asset] = {"source": source_id, "eventLabel": label, "title": title, "icon": icon}
            recipes.append({
                "key": "crm-" + asset,
                "applicationCode": "ARTGIAN_CRM",
                "name": title,
                "eventType": event_type,
                "conditions": [condition("channel", source["channel"]), *conditions],
                "priority": "HIGH" if event_type == "CRM_SYNC_FAILED" else "NORMAL",
                "artwork": asset,
                "templateBody": "*{{title}}*\n{{message}}\n{{url}}",
                "templateVariables": ["title", "message", "url"],
                "targetPolicy": "operations-contact",
            })
    asset = "artgian-crm-teste-de-notificacao"
    artworks[asset] = {"source": "artgian-crm", "eventLabel": "Teste de Notificação", "title": "Artgian CRM - Teste de Notificação", "icon": "bell"}
    recipes.append({
        "key": "crm-test", "applicationCode": "ARTGIAN_CRM", "name": artworks[asset]["title"],
        "eventType": "CRM_NOTIFY_TEST", "conditions": [], "priority": "NORMAL", "artwork": asset,
        "templateBody": "*{{title}}*\n{{message}}\n{{url}}", "templateVariables": ["title", "message", "url"],
        "targetPolicy": "operations-contact",
    })
    for key, asset_suffix, event_type, conditions in [
        ("new-order", "novo-pedido", "ORDER_PAID", []),
        ("posted", "pedido-enviado", "TRACKING_POSTED", []),
        ("out-for-delivery", "pedido-saiu-para-entrega", "TRACKING_STATUS_CHANGED", [condition("status", "OUT_FOR_DELIVERY")]),
        ("delivered", "pedido-entregue", "TRACKING_STATUS_CHANGED", [condition("status", "DELIVERED")]),
    ]:
        asset = "artgian-site-" + asset_suffix
        recipes.append({
            "key": "studio-" + key, "applicationCode": "ARTGIAN", "name": artworks[asset]["title"],
            "eventType": event_type, "conditions": conditions, "priority": "NORMAL", "artwork": asset,
            "templateTitle": artworks[asset]["title"],
            "templateBodyPolicy": "Replace the existing title only; preserve the existing remaining body and variables.",
            "targetPolicy": "operations-contact",
        })
    return {"version": 3, "width": WIDTH, "height": HEIGHT, "maxBytes": 500000,
            "provenance": {"method": "Original code-native typography and geometric icons; SVG and JPEG generated from shared primitives, without raster-image editing.", "generator": "infrastructure/build-event-notification-assets.py", "font": "Arial Bold (or --font equivalent)", "imageGenerationService": None},
            "sources": SOURCES, "artworks": artworks, "recipes": recipes,
            "activationNotes": [
                "Keep only ARTGIAN_CRM and ARTGIAN (display names Artgian CRM and Artgian Studio).",
                "Existing broad CRM_* and overlapping Studio rules must be removed or disabled before exact rules are activated; every matching rule sends independently.",
                "Resolve all targets to the authorized test contact before testing and to the authorized final contact afterward. Do not retain EVENT_RECIPIENT routing for this internal notification rollout.",
                "Keep the same channel and approved Evolution template body; upload each artwork once and set template.spec.imageId.",
                "For Meta, an image header still requires provider approval; this inventory grants no approval.",
                "Application imageId remains a generic fallback only. Exact templates override it and retries retain the chosen image ID.",
            ]}


class Card:
    def __init__(self):
        self.image = Image.new("RGB", (WIDTH * SCALE, HEIGHT * SCALE), "white")
        self.draw = ImageDraw.Draw(self.image)
        self.svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{WIDTH}" height="{HEIGHT}" viewBox="0 0 {WIDTH} {HEIGHT}">']

    def rect(self, box, fill, radius=0, stroke=None, width=1):
        x0, y0, x1, y1 = box
        self.draw.rounded_rectangle(tuple(v * SCALE for v in box), radius=radius * SCALE, fill=fill, outline=stroke, width=width * SCALE)
        self.svg.append(f'<rect x="{x0}" y="{y0}" width="{x1-x0}" height="{y1-y0}" rx="{radius}" fill="{fill}"' + (f' stroke="{stroke}" stroke-width="{width}"' if stroke else '') + '/>')

    def line(self, points, fill, width=4):
        self.draw.line([(x * SCALE, y * SCALE) for x, y in points], fill=fill, width=width * SCALE, joint="curve")
        self.svg.append(f'<polyline points="{" ".join(f"{x},{y}" for x,y in points)}" fill="none" stroke="{fill}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"/>')

    def circle(self, x, y, radius, fill=None, stroke=None, width=4):
        self.draw.ellipse(((x-radius)*SCALE, (y-radius)*SCALE, (x+radius)*SCALE, (y+radius)*SCALE), fill=fill, outline=stroke, width=width*SCALE)
        self.svg.append(f'<circle cx="{x}" cy="{y}" r="{radius}" fill="{fill or "none"}"' + (f' stroke="{stroke}" stroke-width="{width}"' if stroke else '') + '/>')

    def text(self, x, baseline, text, size, color, font_path):
        font = ImageFont.truetype(str(font_path), size*SCALE)
        self.draw.text((x*SCALE, baseline*SCALE), text, fill=color, font=font, anchor="ls")
        self.svg.append(f'<text x="{x}" y="{baseline}" fill="{color}" font-family="Arial, Helvetica, sans-serif" font-size="{size}" font-weight="700">{html.escape(text)}</text>')


def draw_icon(card, icon, color):
    # One original set of geometric line icons, in a 112 × 112 viewport.
    def line(points, width=5):
        card.line([(845+x, 164+y) for x,y in points], color, width)
    def circle(x,y,r):
        card.circle(845+x, 164+y, r, stroke=color, width=5)
    def rect(box, radius=4):
        card.rect(tuple((845 if i % 2 == 0 else 164) + v for i,v in enumerate(box)), "#FFFFFF", radius, color, 5)
    if icon == "box":
        line([(-37,-23),(0,-42),(37,-23),(37,25),(0,43),(-37,25),(-37,-23),(0,-5),(37,-23)])
        line([(0,-5),(0,43)])
        line([(-18,-32),(18,-14),(18,5)])
    elif icon in ("check", "cross"):
        circle(0,0,42)
        if icon == "check": line([(-22,0),(-6,17),(25,-18)], 6)
        else:
            line([(-15,-15),(15,15)],6)
            line([(-15,15),(15,-15)],6)
    elif icon == "alert":
        line([(0,-44),(44,35),(-44,35),(0,-44)])
        line([(0,-17),(0,10)],6)
        card.circle(845, 187, 3, fill=color)
    elif icon == "return":
        line([(-35,-12),(17,-12),(35,6),(35,24),(18,40),(-18,40)])
        line([(-14,-33),(-36,-12),(-14,9)])
    elif icon == "truck":
        rect((-45,-27,10,24),3)
        line([(10,-14),(29,-14),(44,4),(44,24),(10,24)])
        line([(15,-9),(15,6),(38,6)])
        card.circle(816,192,9,fill="#FFFFFF",stroke=color,width=5)
        card.circle(873,192,9,fill="#FFFFFF",stroke=color,width=5)
    elif icon == "receipt":
        line([(-31,43),(-31,-43),(31,-43),(31,43),(16,34),(0,43),(-16,34),(-31,43)])
        line([(-16,-22),(16,-22)],4)
        line([(-16,-4),(16,-4)],4)
        line([(-16,14),(6,14)],4)
    elif icon == "link":
        line([(-17,16),(-28,27),(-43,12),(-43,0),(-15,-28),(-3,-28),(12,-13),(1,-2)])
        line([(17,-16),(28,-27),(43,-12),(43,0),(15,28),(3,28),(-12,13),(-1,2)])
        line([(-10,10),(10,-10)])
    elif icon == "bell":
        line([(-32,22),(-24,9),(-24,-18),(-10,-35),(10,-35),(24,-18),(24,9),(32,22),(-32,22)])
        line([(-9,34),(0,40),(9,34)])
        line([(0,-35),(0,-44)])


def wrapped(label, font_path):
    font = ImageFont.truetype(str(font_path), 58*SCALE)
    lines = []
    for word in label.split():
        candidate = (lines[-1] + " " + word) if lines else word
        if lines and font.getlength(candidate) / SCALE <= 670:
            lines[-1] = candidate
        else:
            lines.append(word)
    assert len(lines) <= 2, (label, lines)
    return lines


def render_card(artwork, source, font_path):
    card = Card()
    card.rect((0,0,960,320),source["tint"])
    card.rect((18,18,942,302),"#FFFFFF",20)
    card.rect((769,38,922,282),source["tint"],14)
    card.rect((41,43,49,74),source["accent"],4)
    card.text(64,68,source["name"],27,source["ink"],font_path)
    label = artwork["eventLabel"]
    lines = wrapped(label,font_path)
    for index, line in enumerate(lines):
        card.text(42,(190 if len(lines)==1 else 157)+index*68,line,58,"#182B3D",font_path)
    card.rect((42,264,107,270),source["accent"],3)
    draw_icon(card,artwork["icon"],source["ink"])
    card.svg.append("</svg>")
    return card


def matches(recipe, event_type, data):
    if recipe["eventType"] != event_type:
        return False
    for c in recipe["conditions"]:
        field = c["field"].split(".")[1]
        if field not in data or data[field] is None:
            return False
        equals = data[field] == c["value"]
        if equals != (c["operator"] == "EQUALS"):
            return False
    return True


def validate(matrix):
    assert len(matrix["artworks"]) == 40
    assert len(matrix["recipes"]) == 44
    assert len({r["key"] for r in matrix["recipes"]}) == 44
    for recipe in matrix["recipes"]:
        data = {}
        for c in recipe["conditions"]:
            data[c["field"].split(".")[1]] = c["value"] if c["operator"] == "EQUALS" else "upstream_unavailable"
        found = [r for r in matrix["recipes"] if r["applicationCode"] == recipe["applicationCode"] and matches(r, recipe["eventType"], data)]
        assert [r["key"] for r in found] == [recipe["key"]], (recipe["key"], found)
        assert recipe["artwork"] in matrix["artworks"]
    # No ambiguous artwork for unknown source/status, nor accidental type prefix routing.
    for event_type, data in [
        ("CRM_ORDER_IMPORTED", {"channel":"Unknown"}),
        ("CRM_ORDER_PAYMENT_ISSUE", {"channel":"Shopee", "paymentStatus":"paid"}),
        ("CRM_ORDER_FULFILLMENT_UPDATED", {"channel":"Shopee", "fulfillmentStatus":"preparing"}),
        ("CRM_SYNC_FAILED", {"channel":"Shopee"}),
        ("CRM_ORDER_IMPORTED_EXTRA", {"channel":"Shopee"}),
    ]:
        assert not any(matches(r,event_type,data) for r in matrix["recipes"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", type=Path, help="Path to Arial Bold or equivalent bold TrueType font")
    args = parser.parse_args()
    fonts = [args.font, Path("/System/Library/Fonts/Supplemental/Arial Bold.ttf"), Path("/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf"), Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf")]
    font = next((f for f in fonts if f and f.exists()), None)
    if not font:
        parser.error("No supported font found. Supply --font path/to/bold.ttf")
    matrix = inventory()
    validate(matrix)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    previews = []
    for slug, artwork in matrix["artworks"].items():
        card = render_card(artwork, SOURCES[artwork["source"]], font)
        svg, jpeg = OUTPUT / (slug+".svg"), OUTPUT / (slug+".jpg")
        svg.write_text("\n".join(card.svg)+"\n", encoding="utf-8")
        final = card.image.resize((WIDTH,HEIGHT),Image.Resampling.LANCZOS)
        final.save(jpeg,quality=90,optimize=True,subsampling=0)
        with Image.open(jpeg) as check:
            assert check.size == (WIDTH,HEIGHT) and check.format == "JPEG"
        assert 0 < jpeg.stat().st_size <= 500000
        artwork.update({"file":str(jpeg.relative_to(ROOT)),"svg":str(svg.relative_to(ROOT)),"byteSize":jpeg.stat().st_size,"sha256":hashlib.sha256(jpeg.read_bytes()).hexdigest()})
        previews.append(final.resize((480,160),Image.Resampling.LANCZOS))
    sheet = Image.new("RGB",(480*4,160*10),"white")
    for i,preview in enumerate(previews):
        sheet.paste(preview,((i%4)*480,(i//4)*160))
    sheet.save(OUTPUT/"contact-sheet.jpg",quality=90)
    (ROOT/"infrastructure/event-notification-matrix.json").write_text(json.dumps(matrix,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(f"Validated {len(matrix['recipes'])} disjoint recipes and {len(matrix['artworks'])} images at {OUTPUT}")
    print("All images: 960 × 320 JPEG, <= 500 KB; editable original SVG included.")


if __name__ == "__main__":
    main()
