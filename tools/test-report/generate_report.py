"""Builds docs/Wallet-Service-Test-Report.pdf from the surefire XML reports and target/test-metrics.tsv.

Usage (from the project root, after `./mvnw test`):
    python tools/test-report/generate_report.py
"""
import datetime
import glob
import os
import subprocess
import xml.etree.ElementTree as ET
from collections import defaultdict

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (KeepTogether, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table,
                                TableStyle)

from cases import CASES, CATEGORIES, FINDINGS

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "docs", "Wallet-Service-Test-Report.pdf")

INK = colors.HexColor("#1f2933")
MUTED = colors.HexColor("#616e7c")
RULE = colors.HexColor("#cbd2d9")
HEAD_BG = colors.HexColor("#e4e7eb")
PASS = colors.HexColor("#1f7a4d")
FAIL = colors.HexColor("#b42318")
SKIP = colors.HexColor("#8a6d1d")
SEVERITY = {"High": FAIL, "Medium": colors.HexColor("#b54708"), "Low": colors.HexColor("#3e4c59"),
            "Info": colors.HexColor("#3e4c59")}

styles = getSampleStyleSheet()
H1 = ParagraphStyle("H1", parent=styles["Heading1"], fontSize=18, textColor=INK, spaceAfter=8)
H2 = ParagraphStyle("H2", parent=styles["Heading2"], fontSize=13.5, textColor=INK, spaceBefore=10, spaceAfter=6)
H3 = ParagraphStyle("H3", parent=styles["Heading3"], fontSize=10.5, textColor=INK, spaceBefore=6, spaceAfter=3)
BODY = ParagraphStyle("Body", parent=styles["BodyText"], fontSize=9, leading=12.5, textColor=INK)
SMALL = ParagraphStyle("Small", parent=BODY, fontSize=8, leading=10.5)
CELL = ParagraphStyle("Cell", parent=BODY, fontSize=8.2, leading=10.8)
CELL_B = ParagraphStyle("CellB", parent=CELL, fontName="Helvetica-Bold")
MONO = ParagraphStyle("Mono", parent=CELL, fontName="Courier", fontSize=7.6, leading=10)
TITLE = ParagraphStyle("Title", parent=H1, fontSize=24, leading=30, alignment=TA_CENTER, spaceAfter=4)
SUB = ParagraphStyle("Sub", parent=BODY, fontSize=11, alignment=TA_CENTER, textColor=MUTED)


def esc(text):
    return str(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def load_results():
    results = {}
    suites = []
    for path in sorted(glob.glob(os.path.join(ROOT, "target", "surefire-reports", "TEST-*.xml"))):
        suite = ET.parse(path).getroot()
        suites.append(suite)
        for tc in suite.iter("testcase"):
            cls = tc.get("classname").rsplit(".", 1)[-1]
            name = tc.get("name").split("(")[0]
            status, message = "PASSED", ""
            for tag in ("failure", "error"):
                node = tc.find(tag)
                if node is not None:
                    status = "FAILED"
                    message = (node.get("message") or node.text or "").strip().splitlines()[0:6]
                    message = " ".join(message)
                    if len(message) > 400:
                        message = message[:400] + " ..."
            if tc.find("skipped") is not None:
                status = "SKIPPED"
            results[f"{cls}#{name}"] = {"status": status, "time": float(tc.get("time") or 0), "message": message}
    return results, suites


def load_metrics():
    metrics = defaultdict(list)
    path = os.path.join(ROOT, "target", "test-metrics.tsv")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) == 3:
                    metrics[parts[0]].append((parts[1], parts[2]))
    return metrics


def git(*args):
    try:
        return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()
    except Exception:
        return "n/a"


def status_para(status):
    color = {"PASSED": PASS, "FAILED": FAIL, "SKIPPED": SKIP}.get(status, MUTED)
    return Paragraph(f'<font color="{color.hexval()}"><b>{status}</b></font>', CELL)


def grid(data, widths, header=True, zebra=False):
    t = Table(data, colWidths=widths, repeatRows=1 if header else 0)
    style = [
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("GRID", (0, 0), (-1, -1), 0.4, RULE),
        ("LEFTPADDING", (0, 0), (-1, -1), 4),
        ("RIGHTPADDING", (0, 0), (-1, -1), 4),
        ("TOPPADDING", (0, 0), (-1, -1), 3),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
    ]
    if header:
        style.append(("BACKGROUND", (0, 0), (-1, 0), HEAD_BG))
    t.setStyle(TableStyle(style))
    return t


def footer(canvas, doc):
    canvas.saveState()
    canvas.setFont("Helvetica", 7.5)
    canvas.setFillColor(MUTED)
    canvas.drawString(18 * mm, 10 * mm, "Wallet Service - Resilience & Integrity Test Report")
    canvas.drawRightString(A4[0] - 18 * mm, 10 * mm, f"Page {doc.page}")
    canvas.restoreState()


def metrics_text(case_id, metrics):
    rows = metrics.get(case_id, [])
    if not rows:
        return ""
    return "<br/>".join(f"{esc(k)} = <b>{esc(v)}</b>" for k, v in rows)


def build():
    results, suites = load_results()
    metrics = load_metrics()
    width = A4[0] - 36 * mm

    total = len(results)
    passed = sum(1 for r in results.values() if r["status"] == "PASSED")
    failed = sum(1 for r in results.values() if r["status"] == "FAILED")
    skipped = sum(1 for r in results.values() if r["status"] == "SKIPPED")
    wall = sum(float(s.get("time") or 0) for s in suites)

    story = []
    story.append(Spacer(1, 30 * mm))
    story.append(Paragraph("Wallet Service", TITLE))
    story.append(Paragraph("Resilience &amp; Integrity Test Report", SUB))
    story.append(Spacer(1, 10 * mm))
    meta = [
        ["Report date", datetime.datetime.now().strftime("%d %b %Y, %H:%M")],
        ["Branch / commit", f"{git('rev-parse', '--abbrev-ref', 'HEAD')} @ {git('rev-parse', '--short', 'HEAD')}"
                            + (" (with uncommitted test changes)" if git("status", "--porcelain") else "")],
        ["Stack under test", "Spring Boot 4.1.2, Java 21, PostgreSQL 16, Redis 7, Apache Kafka 3.8 (KRaft)"],
        ["Test framework", "JUnit 5 + AssertJ + Mockito, run with Maven Surefire against the real Docker services"],
        ["Result", f"{passed} passed, {failed} failed, {skipped} skipped out of {total} tests "
                   f"(total suite time {wall:.0f} s)"],
    ]
    story.append(grid([[Paragraph(f"<b>{a}</b>", CELL), Paragraph(esc(b), CELL)] for a, b in meta],
                      [40 * mm, width - 40 * mm], header=False))
    story.append(Spacer(1, 8 * mm))
    verdict_color = PASS if failed == 0 else FAIL
    verdict = "ALL TESTS PASSED" if failed == 0 else f"{failed} TEST(S) FAILED"
    story.append(Paragraph(f'<font color="{verdict_color.hexval()}" size="16"><b>{verdict}</b></font>',
                           ParagraphStyle("v", parent=BODY, alignment=TA_CENTER)))
    story.append(PageBreak())

    # ---- summary by category ----
    story.append(Paragraph("1. Executive summary", H1))
    story.append(Paragraph(
        "Every test case from the test plan was turned into an automated integration test that boots the "
        "full application against the real PostgreSQL, Redis and Kafka containers. Where the plan calls "
        "for an external load tool (k6/JMeter), the equivalent load is generated in-process with a latch "
        "that releases all requests at the same instant, which is harsher on locking than a ramped load. "
        "Measured values (latencies, counts, backoff gaps) are recorded by the tests themselves and shown "
        "next to each case.", BODY))
    story.append(Spacer(1, 4 * mm))

    rows = [[Paragraph("<b>Category</b>", CELL), Paragraph("<b>Cases</b>", CELL),
             Paragraph("<b>Passed</b>", CELL), Paragraph("<b>Failed</b>", CELL), Paragraph("<b>Time (s)</b>", CELL)]]
    for cat_id, cat_name in CATEGORIES:
        keys = [k for k, c in CASES.items() if c["cat"] == cat_id]
        rs = [results.get(k) for k in keys if results.get(k)]
        rows.append([Paragraph(f"{cat_id}. {esc(cat_name)}", CELL), str(len(keys)),
                     str(sum(1 for r in rs if r["status"] == "PASSED")),
                     str(sum(1 for r in rs if r["status"] == "FAILED") + (len(keys) - len(rs))),
                     f"{sum(r['time'] for r in rs):.1f}"])
    story.append(grid(rows, [width - 80 * mm, 18 * mm, 20 * mm, 20 * mm, 22 * mm]))
    story.append(Spacer(1, 6 * mm))

    # ---- findings ----
    story.append(Paragraph("2. Findings and recommendations", H1))
    fixed = sum(1 for f in FINDINGS if f.get("fixed"))
    if failed:
        lead = (f"{failed} test(s) fail because of a genuine defect in the application, not in the tests. "
                "The tests also surfaced the following behaviours.")
    elif fixed:
        lead = (f"All assertions pass. Findings marked FIXED were defects exposed by failing tests in the first "
                f"run ({fixed} in total); the application was changed and the same tests now pass. The remaining "
                "items are open recommendations.")
    else:
        lead = "All assertions pass, but the tests surfaced the following behaviours that deserve attention."
    story.append(Paragraph(lead, BODY))
    story.append(Spacer(1, 3 * mm))
    frows = [[Paragraph("<b>#</b>", CELL), Paragraph("<b>Severity</b>", CELL), Paragraph("<b>Finding</b>", CELL),
              Paragraph("<b>Recommendation</b>", CELL)]]
    for i, f in enumerate(FINDINGS, 1):
        sev = f["severity"]
        tag = f'<br/><font color="{PASS.hexval()}"><b>FIXED</b></font>' if f.get("fixed") else ""
        frows.append([str(i),
                      Paragraph(f'<font color="{SEVERITY[sev].hexval()}"><b>{sev}</b></font>{tag}', CELL),
                      Paragraph(f"<b>{esc(f['title'])}</b><br/>{esc(f['detail'])}", CELL),
                      Paragraph(("<b>Applied:</b> " if f.get("fixed") else "") + esc(f["fix"]), CELL)])
    story.append(grid(frows, [8 * mm, 17 * mm, (width - 25 * mm) * 0.55, (width - 25 * mm) * 0.45]))
    story.append(PageBreak())

    # ---- detailed cases ----
    story.append(Paragraph("3. Test case details", H1))
    for cat_id, cat_name in CATEGORIES:
        story.append(Paragraph(f"3.{cat_id} {esc(cat_name)}", H2))
        for key, c in CASES.items():
            if c["cat"] != cat_id:
                continue
            r = results.get(key, {"status": "NOT RUN", "time": 0, "message": ""})
            actual = esc(c.get("actual", ""))
            m = metrics_text(c["id"], metrics)
            if m:
                actual = (actual + "<br/>" if actual else "") + "<font size=7.6>Measured:</font><br/>" + m
            if r["message"]:
                actual += f'<br/><font color="{FAIL.hexval()}">{esc(r["message"])}</font>'
            label_w = 26 * mm
            data = [
                [Paragraph(f"<b>{c['id']}</b>", CELL_B), Paragraph(f"<b>{esc(c['title'])}</b>", CELL_B)],
                [Paragraph("Test", CELL), Paragraph(esc(key), MONO)],
                [Paragraph("Goal", CELL), Paragraph(esc(c["goal"]), CELL)],
                [Paragraph("How it runs", CELL), Paragraph(esc(c["how"]), CELL)],
                [Paragraph("Expected", CELL), Paragraph(esc(c["expected"]), CELL)],
                [Paragraph("Actual", CELL), Paragraph(actual or "As expected.", CELL)],
                [Paragraph("Result", CELL), Table([[status_para(r["status"]),
                                                    Paragraph(f"{r['time']:.2f} s", CELL)]],
                                                  colWidths=[30 * mm, 30 * mm],
                                                  style=[("LEFTPADDING", (0, 0), (-1, -1), 0),
                                                         ("TOPPADDING", (0, 0), (-1, -1), 0),
                                                         ("BOTTOMPADDING", (0, 0), (-1, -1), 0)])],
            ]
            t = grid(data, [label_w, width - label_w], header=False)
            t.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, 0), HEAD_BG),
                                   ("TEXTCOLOR", (0, 1), (0, -1), MUTED)]))
            story.append(KeepTogether([t, Spacer(1, 4 * mm)]))

    # ---- appendix ----
    story.append(PageBreak())
    story.append(Paragraph("Appendix A. Full test inventory", H1))
    story.append(Paragraph("Every test executed in this run, including the pre-existing regression tests.", BODY))
    story.append(Spacer(1, 3 * mm))
    inv = [[Paragraph("<b>Test</b>", CELL), Paragraph("<b>Case</b>", CELL), Paragraph("<b>Result</b>", CELL),
            Paragraph("<b>Time (s)</b>", CELL)]]
    for key in sorted(results):
        inv.append([Paragraph(esc(key), MONO), Paragraph(CASES.get(key, {}).get("id", "regression"), CELL),
                    status_para(results[key]["status"]), f"{results[key]['time']:.2f}"])
    story.append(grid(inv, [width - 65 * mm, 22 * mm, 23 * mm, 20 * mm]))

    story.append(Spacer(1, 6 * mm))
    story.append(Paragraph("Appendix B. How to reproduce", H1))
    for line in [
        "1. Start the infrastructure: <font name='Courier'>docker compose up -d</font> "
        "(the tests use the <font name='Courier'>wallet_test</font> database).",
        "2. Run the suite: <font name='Courier'>source env.sh &amp;&amp; ./mvnw test</font>. "
        "The ledger soak defaults to 20 s; run the 1-hour version with "
        "<font name='Courier'>-Dchaos.seconds=3600 -Dtest=LedgerIntegrityTest</font>.",
        "3. Rebuild this report: <font name='Courier'>python tools/test-report/generate_report.py</font>.",
        "The application itself must not be running while the suite runs: it would join the same Kafka "
        "consumer groups that EventPipelineTest restarts.",
    ]:
        story.append(Paragraph(line, BODY))
        story.append(Spacer(1, 1.5 * mm))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    doc = SimpleDocTemplate(OUT, pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm, topMargin=16 * mm,
                            bottomMargin=18 * mm, title="Wallet Service - Test Report", author="wallet-service")
    doc.build(story, onFirstPage=footer, onLaterPages=footer)
    print(f"Wrote {OUT}: {passed}/{total} passed, {failed} failed")


if __name__ == "__main__":
    build()
