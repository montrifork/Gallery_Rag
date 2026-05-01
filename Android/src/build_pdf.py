#!/usr/bin/env python3
"""Convert the health triage knowledge base markdown to a styled PDF using fpdf2."""
import re
from fpdf import FPDF

SRC = "/Users/amine/AI/gallery/Android/src/health_triage_kb.md"
OUT = "/Users/amine/AI/gallery/Android/src/health_triage_kb.pdf"

REPLACEMENTS = {
    "\u2014": "--",   # em dash
    "\u2013": "-",    # en dash
    "\u2018": "'", "\u2019": "'",
    "\u201c": '"', "\u201d": '"',
    "\u2026": "...",
    "\u2022": "*",
    "\u2192": "->",
    "\u2265": ">=",
    "\u2264": "<=",
    "\u00a0": " ",
    "\u00bd": "1/2",
    "\u00b0": " deg ",
}

def sanitize(s: str) -> str:
    for k, v in REPLACEMENTS.items():
        s = s.replace(k, v)
    return s.encode("latin-1", errors="replace").decode("latin-1")


class PDF(FPDF):
    def header(self):
        if self.page_no() == 1:
            return
        self.set_font("Helvetica", "I", 9)
        self.set_text_color(120)
        self.cell(0, 8, sanitize("Health Issue Triage & Claim Assistant -- Knowledge Base"),
                 align="L")
        self.ln(10)
        self.set_text_color(0)

    def footer(self):
        self.set_y(-12)
        self.set_font("Helvetica", "I", 8)
        self.set_text_color(120)
        self.cell(0, 8, f"Page {self.page_no()}", align="C")
        self.set_text_color(0)


def render_inline(pdf: FPDF, text: str, size: int = 11):
    """Render text honoring **bold** spans on a single line via multi_cell segments."""
    # Simple approach: split by ** and toggle bold; emit using write()
    parts = re.split(r"(\*\*[^*]+\*\*)", text)
    pdf.set_font("Helvetica", "", size)
    for part in parts:
        if part.startswith("**") and part.endswith("**"):
            pdf.set_font("Helvetica", "B", size)
            pdf.write(6, part[2:-2])
            pdf.set_font("Helvetica", "", size)
        else:
            pdf.write(6, part)
    pdf.ln(7)


def main():
    with open(SRC, "r", encoding="utf-8") as f:
        lines = sanitize(f.read()).splitlines()

    pdf = PDF(format="A4")
    pdf.set_auto_page_break(auto=True, margin=18)
    pdf.set_margins(left=18, top=18, right=18)
    pdf.add_page()

    # Cover
    pdf.set_font("Helvetica", "B", 22)
    pdf.ln(40)
    pdf.multi_cell(0, 10, "Health Issue Triage\n& Claim Assistant", align="C")
    pdf.ln(4)
    pdf.set_font("Helvetica", "I", 13)
    pdf.multi_cell(0, 8, "Knowledge Base for On-Device RAG Assistant", align="C")
    pdf.ln(20)
    pdf.set_font("Helvetica", "", 10)
    pdf.set_text_color(90)
    pdf.multi_cell(0, 6,
        "This document is the retrieval source for an on-device assistant "
        "that helps insurance-app users triage health issues, attempt safe "
        "self-care, escalate to professional care when needed, and submit "
        "a claim if applicable.", align="C")
    pdf.set_text_color(0)
    pdf.add_page()

    in_blockquote = False
    for raw in lines:
        line = raw.rstrip()

        if not line.strip():
            pdf.ln(3)
            in_blockquote = False
            continue

        # Horizontal rule
        if line.strip() == "---":
            pdf.ln(2)
            y = pdf.get_y()
            pdf.set_draw_color(200)
            pdf.line(pdf.l_margin, y, pdf.w - pdf.r_margin, y)
            pdf.set_draw_color(0)
            pdf.ln(4)
            continue

        # Headings
        if line.startswith("# "):
            pdf.set_font("Helvetica", "B", 18)
            pdf.set_text_color(20, 40, 90)
            pdf.multi_cell(0, 9, line[2:].strip())
            pdf.set_text_color(0)
            pdf.ln(2)
            continue
        if line.startswith("## "):
            pdf.ln(3)
            pdf.set_font("Helvetica", "B", 15)
            pdf.set_text_color(20, 40, 90)
            pdf.multi_cell(0, 8, line[3:].strip())
            pdf.set_text_color(0)
            pdf.ln(1)
            continue
        if line.startswith("### "):
            pdf.ln(2)
            pdf.set_font("Helvetica", "B", 12)
            pdf.set_text_color(60, 60, 60)
            pdf.multi_cell(0, 7, line[4:].strip())
            pdf.set_text_color(0)
            pdf.ln(1)
            continue

        # Blockquote (lines starting with >)
        if line.lstrip().startswith(">"):
            content = line.lstrip()[1:].strip()
            if not in_blockquote:
                pdf.ln(1)
                in_blockquote = True
            pdf.set_fill_color(245, 245, 250)
            pdf.set_font("Helvetica", "I", 10)
            x_start = pdf.l_margin
            pdf.set_x(x_start)
            pdf.cell(2, 6, "", fill=False)
            pdf.set_x(x_start + 4)
            pdf.multi_cell(0, 6, content, fill=True)
            continue
        else:
            in_blockquote = False

        # Bullet list
        stripped = line.lstrip()
        indent = len(line) - len(stripped)
        if stripped.startswith("- "):
            text = stripped[2:]
            pdf.set_x(pdf.l_margin + 4 + indent)
            pdf.set_font("Helvetica", "", 11)
            pdf.cell(4, 6, "*")
            # render rest with potential bold
            x = pdf.get_x()
            y = pdf.get_y()
            pdf.set_xy(x, y)
            # Use write-based renderer but need wrap; emulate by splitting words
            render_wrapped(pdf, text, size=11, left=x)
            continue

        # Numbered list
        m = re.match(r"^(\d+)\.\s+(.*)$", stripped)
        if m:
            pdf.set_x(pdf.l_margin + 4 + indent)
            pdf.set_font("Helvetica", "B", 11)
            pdf.cell(7, 6, f"{m.group(1)}.")
            x = pdf.get_x()
            render_wrapped(pdf, m.group(2), size=11, left=x)
            continue

        # Plain paragraph (with bold spans)
        render_wrapped(pdf, line, size=11, left=pdf.l_margin)

    pdf.output(OUT)
    print(f"Wrote {OUT}")


def render_wrapped(pdf: FPDF, text: str, size: int, left: float):
    """Wrap text within page width, supporting **bold** spans, starting at given x."""
    right_margin = pdf.w - pdf.r_margin
    line_height = 6
    parts = re.split(r"(\*\*[^*]+\*\*)", text)
    pdf.set_xy(left, pdf.get_y())
    for part in parts:
        bold = part.startswith("**") and part.endswith("**")
        content = part[2:-2] if bold else part
        pdf.set_font("Helvetica", "B" if bold else "", size)
        words = re.split(r"(\s+)", content)
        for w in words:
            if not w:
                continue
            wlen = pdf.get_string_width(w)
            if pdf.get_x() + wlen > right_margin:
                pdf.ln(line_height)
                pdf.set_x(left)
                if w.strip() == "":
                    continue
            pdf.cell(wlen, line_height, w)
    pdf.ln(line_height)


if __name__ == "__main__":
    main()
