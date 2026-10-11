#!/usr/bin/env python3
"""
Exports the Kavira Pharmaceuticals data (make_kavira_data.py) as HiveCore seed data.

Reads kavira_data/ (the generator's output) and writes:
  node/src/main/assets/seed/kavira/*.csv      the tables each role seeds (amounts in INR, not lakh)
  node/src/main/assets/seed/kavira_*.md       two small context documents (company snapshot, HR access policy); the
                                              document seeder reads documents from the seed folder root
  node/src/main/assets/seed/seed_manifest.json  roles -> tables/docs, with the aliases and flags the planner needs
  tools/kavira/out/                           answer keys, JSON context, app_users and the per-branch HR shards

The three roles of the app are the three phones of the demo:
  SALES    sales_monthly, products, branch_master, sales_force, events, stockists   (+ kavira_company_snapshot.md)
           (the stockists live on the stage phone so every Kavira job starts from it)
  HR       staff, compensation, salary_history, devices                   (+ onboarding_checklist.docx, kavira_hr_access_policy.md)
  ACCOUNTS financials, receivables, budget_vs_actual

The legacy tour tables (employees, sales, invoices) stay in the manifest's "tables" section because the planner tests
build their catalogs from them; no role loads them any more.

Python 3.8+, standard library only, deterministic.  Usage: python export_seed.py [kavira_data_dir] [repo_root]
"""
import csv, json, os, shutil, sys
from collections import OrderedDict, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "kavira_data")
REPO = sys.argv[2] if len(sys.argv) > 2 else os.path.abspath(os.path.join(HERE, "..", ".."))
SEED = os.path.join(REPO, "node", "src", "main", "assets", "seed")
KAV = os.path.join(SEED, "kavira")
OUT = os.path.join(HERE, "out")
LAKH = 100_000

os.makedirs(KAV, exist_ok=True)
os.makedirs(OUT, exist_ok=True)


def read(name):
    with open(os.path.join(SRC, name), encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def read_json(name):
    with open(os.path.join(SRC, name), encoding="utf-8") as f:
        return json.load(f)


def inr(lakh):
    return str(int(round(float(lakh) * LAKH)))


def col(name, type_, aliases=(), groupable=False, identifier=False, confidential=False):
    return OrderedDict(name=name, type=type_, confidential=confidential, identifier=identifier, groupable=groupable, aliases=list(aliases))


def write_table(name, rows, columns):
    """columns: list of (new_name, source_name or callable(row)->str, ColumnDef)."""
    with open(os.path.join(KAV, name + ".csv"), "w", encoding="utf-8", newline="") as f:
        wr = csv.writer(f)
        wr.writerow([c[0] for c in columns])
        for r in rows:
            wr.writerow([c[1](r) if callable(c[1]) else r[c[1]] for c in columns])
    return len(rows)


def infer(values):
    """DataStore.inferType: INTEGER if every non-empty value is an int, REAL if a double, else TEXT."""
    kind = "INTEGER"
    for v in values:
        if v == "":
            continue
        try:
            int(v)
        except ValueError:
            try:
                float(v)
                kind = "REAL"
            except ValueError:
                return "TEXT"
    return kind


tables = OrderedDict()   # name -> manifest entry
counts = {}


def add_table(name, rows, columns, aliases, confidential=False):
    n = write_table(name, rows, columns)
    # pin the types to what the import will infer
    with open(os.path.join(KAV, name + ".csv"), encoding="utf-8", newline="") as f:
        data = list(csv.reader(f))
    header, body = data[0], data[1:]
    defs = []
    for i, (new, _, d) in enumerate(columns):
        d["type"] = infer(r[i] for r in body)
        if d["type"] != "TEXT":
            d["groupable"] = False
            d["identifier"] = False
        defs.append(d)
    tables[name] = OrderedDict(file="kavira/%s.csv" % name, rows=n, confidential=confidential, aliases=list(aliases), columns=defs)
    counts[name] = n
    return n


# ============================== SALES ==============================
sales = read("sales_monthly.csv")
add_table("sales_monthly", sales, [
    ("month", "month", col("month", "TEXT", ["months", "period"], groupable=True)),
    ("fiscal_year", "fiscal_year", col("fiscal_year", "TEXT", ["fy", "year", "years", "financial year"], groupable=True)),
    ("fiscal_quarter", "fiscal_quarter", col("fiscal_quarter", "TEXT", ["quarter", "quarters"], groupable=True)),
    ("branch", "branch", col("branch", "TEXT", ["branches", "region", "regions", "office", "offices", "city", "cities", "state"], groupable=True)),
    ("branch_city", "branch_city", col("branch_city", "TEXT", ["branch city"], groupable=True)),
    ("brand", "brand", col("brand", "TEXT", ["brands", "product", "products", "sku", "skus"], groupable=True)),
    ("molecule", "molecule", col("molecule", "TEXT", ["molecules", "generic", "generics"], groupable=True)),
    ("therapy_area", "therapy_area", col("therapy_area", "TEXT", ["therapy", "therapy area", "therapy areas", "category", "categories", "portfolio"], groupable=True)),
    ("segment", "segment", col("segment", "TEXT", ["segments", "business", "businesses", "line of business"], groupable=True)),
    ("channel", "channel", col("channel", "TEXT", ["channels", "route to market"], groupable=True)),
    ("units", "units", col("units", "INTEGER", ["unit", "packs", "pack", "volume", "volumes", "quantity", "qty", "pieces"])),
    ("gross_sales", lambda r: inr(r["gross_sales_inr_lakh"]), col("gross_sales", "INTEGER", ["gross sales", "gross revenue", "gross"])),
    ("net_sales", lambda r: inr(r["net_sales_inr_lakh"]), col("net_sales", "INTEGER", ["sales", "revenue", "revenues", "net sales", "turnover", "income", "sales value", "earnings"])),
], aliases=["sales", "revenue", "sold", "orders", "turnover", "monthly sales", "sales monthly"])

products = read("products.csv")
add_table("products", products, [
    ("product_id", "product_id", col("product_id", "TEXT", ["product id", "id"], identifier=True)),
    ("brand", "brand", col("brand", "TEXT", ["brands", "product", "name"], groupable=True)),
    ("molecule", "molecule", col("molecule", "TEXT", ["molecules", "generic"], groupable=True)),
    ("therapy_area", "therapy_area", col("therapy_area", "TEXT", ["therapy", "therapy area", "category"], groupable=True)),
    ("segment", "segment", col("segment", "TEXT", ["segments"], groupable=True)),
    ("pack", "pack", col("pack", "TEXT", ["pack size", "packs"], groupable=True)),
    ("launch", "launch", col("launch", "TEXT", ["launched", "launch month", "launch date"], groupable=True)),
    ("trade_price", "trade_price_inr_fy2026", col("trade_price", "REAL", ["price", "prices", "trade price", "price per pack"])),
    ("also_on_govt_tender", "also_on_govt_tender", col("also_on_govt_tender", "TEXT", ["tender", "on tender", "government tender"], groupable=True)),
], aliases=["products", "product", "brands", "brand", "portfolio", "catalogue", "range", "price list"])

branches = read("branches.csv")
add_table("branch_master", branches, [
    ("branch", "branch", col("branch", "TEXT", ["branches", "code"], groupable=True)),
    ("city", "city", col("city", "TEXT", ["cities"], groupable=True)),
    ("state_covered", "state_covered", col("state_covered", "TEXT", ["state", "states"], groupable=True)),
    ("opened", "opened", col("opened", "TEXT", ["opened in", "opening"], groupable=True)),
    ("role", "role", col("role", "TEXT", ["roles"], groupable=True)),
], aliases=["branch master", "branch list", "offices", "locations"])

sf = read("sales_force.csv")
add_table("sales_force", sf, [
    ("fiscal_year", "fiscal_year", col("fiscal_year", "TEXT", ["fy", "year", "years"], groupable=True)),
    ("branch", "branch", col("branch", "TEXT", ["branches", "region", "regions"], groupable=True)),
    ("branch_city", "branch_city", col("branch_city", "TEXT", ["city"], groupable=True)),
    ("medical_reps", "medical_reps_at_year_end", col("medical_reps", "INTEGER", ["rep count", "reps at year end", "field force", "mr count"])),
    ("doctors_covered", "doctors_covered", col("doctors_covered", "INTEGER", ["doctors", "doctor coverage"])),
    ("chemists_covered", "chemists_covered", col("chemists_covered", "INTEGER", ["chemists", "chemist coverage", "pharmacies covered"])),
    ("branded_sales", lambda r: inr(r["branded_sales_inr_lakh"]), col("branded_sales", "INTEGER", ["branded sales", "branded revenue"])),
    ("branded_sales_per_rep", lambda r: inr(r["sales_per_rep_inr_lakh"]), col("branded_sales_per_rep", "INTEGER", ["productivity", "rep productivity", "branded sales per rep"])),
], aliases=["sales force", "field force", "coverage", "productivity"])

events = read("events.csv")
add_table("events", events, [
    ("month", "month", col("month", "TEXT", ["months", "when"], groupable=True)),
    ("type", "type", col("type", "TEXT", ["types", "kind", "category"], groupable=True)),
    ("title", "title", col("title", "TEXT", ["event", "headline"], identifier=True)),
    ("description", "description", col("description", "TEXT", ["details", "what happened"])),
    ("impact", "impact", col("impact", "TEXT", ["effect", "positive or negative"], groupable=True)),
], aliases=["events", "event", "business events", "timeline", "what happened", "milestones"])

# ============================== HR ==============================
emps = read("employees.csv")
HR_DIR = [
    ("employee_id", "employee_id", col("employee_id", "TEXT", ["employee id", "id", "staff id"], identifier=True)),
    ("name", "name", col("name", "TEXT", ["names", "employee name", "called", "named"], identifier=True)),
    ("department", "department", col("department", "TEXT", ["departments", "function", "functions", "team", "teams"], groupable=True)),
    ("designation", "designation", col("designation", "TEXT", ["designations", "role", "roles", "title", "titles", "job title", "grade name"], groupable=True)),
    ("branch", "branch", col("branch", "TEXT", ["branches", "region", "regions", "office", "offices", "city", "cities"], groupable=True)),
    ("work_location", "work_location", col("work_location", "TEXT", ["location", "locations", "site", "sites", "where they work"], groupable=True)),
    ("territory", "territory", col("territory", "TEXT", ["territories", "area", "areas", "town", "towns"], groupable=True)),
    ("area_code", "area_code", col("area_code", "TEXT", ["area code", "area codes", "asm area"], groupable=True)),
    ("date_of_joining", "date_of_joining", col("date_of_joining", "TEXT", ["joined", "joining date", "join date", "hired", "start date"])),
    ("tenure_years", "tenure_years", col("tenure_years", "REAL", ["tenure", "years of service", "experience"])),
    ("grade", "grade", col("grade", "TEXT", ["grades", "band", "bands", "level", "levels"], groupable=True)),
    ("reporting_manager_id", "reporting_manager_id", col("reporting_manager_id", "TEXT", ["manager", "manager id", "reports to"], identifier=True)),
]
add_table("staff", emps, HR_DIR, aliases=["staff", "employees", "employee", "people", "headcount", "head count", "workforce", "team",
                                          "reps", "rep", "medical reps", "medical rep", "medical representatives", "mrs", "field staff", "directory"])

comp_by = {c["employee_id"]: c for c in read("compensation.csv")}
comp_rows = [dict(e, **comp_by[e["employee_id"]]) for e in emps]
add_table("compensation", comp_rows, HR_DIR + [
    ("fiscal_year", "fiscal_year", col("fiscal_year", "TEXT", ["fy", "year"], groupable=True)),
    ("ctc", "ctc_annual_inr", col("ctc", "INTEGER", ["salary", "salaries", "pay", "wage", "wages", "annual ctc", "cost to company", "package", "compensation"], confidential=True)),
    ("fixed_pay", "fixed_annual_inr", col("fixed_pay", "INTEGER", ["fixed", "fixed salary", "basic", "fixed component"], confidential=True)),
    ("variable_target", "variable_target_inr", col("variable_target", "INTEGER", ["variable", "variable pay", "target incentive", "bonus target"], confidential=True)),
    ("incentive_paid", "incentive_paid_fy2026_inr", col("incentive_paid", "INTEGER", ["incentive", "incentives", "bonus", "bonuses", "commission", "commissions", "payout"], confidential=True)),
    ("travel_allowance", "monthly_travel_allowance_inr", col("travel_allowance", "INTEGER", ["ta", "travel", "allowance", "allowances", "monthly travel allowance"], confidential=True)),
], aliases=["compensation", "payroll", "pay data", "salary data", "ctc data", "remuneration"], confidential=True)

hist = read("salary_history.csv")
add_table("salary_history", hist, [
    ("employee_id", "employee_id", col("employee_id", "TEXT", ["employee id", "id"], identifier=True)),
    ("fiscal_year", "fiscal_year", col("fiscal_year", "TEXT", ["fy", "year", "years"], groupable=True)),
    ("ctc", "ctc_annual_inr", col("ctc", "INTEGER", ["salary", "pay", "annual ctc", "historical salary"], confidential=True)),
    ("increment_pct", "increment_pct", col("increment_pct", "REAL", ["increment", "increments", "raise", "raises", "hike", "hikes", "increment percent"], confidential=True)),
], aliases=["salary history", "pay history", "increments", "increment history", "raises", "hikes"], confidential=True)

devices = read("devices.csv")
add_table("devices", devices, [
    ("device_id", "device_id", col("device_id", "TEXT", ["device id", "phone id", "id"], identifier=True)),
    ("assigned_employee_id", "assigned_employee_id", col("assigned_employee_id", "TEXT", ["employee id", "assigned to", "owner"], identifier=True)),
    ("designation", "designation", col("designation", "TEXT", ["designations", "role", "roles"], groupable=True)),
    ("branch", "branch", col("branch", "TEXT", ["branches", "region", "regions", "office", "city"], groupable=True)),
    ("usually_at", "usually_at", col("usually_at", "TEXT", ["location", "where", "gathering", "site"], groupable=True)),
    ("tier", "tier", col("tier", "TEXT", ["tiers", "class", "grade", "segment"], groupable=True)),
    ("ram_gb", "ram_gb", col("ram_gb", "INTEGER", ["ram", "memory", "gb ram"])),
    ("storage_gb", "storage_gb", col("storage_gb", "INTEGER", ["storage", "gb storage"])),
    ("chipset_class", "chipset_class", col("chipset_class", "TEXT", ["chipset", "soc", "processor class"], groupable=True)),
    ("has_npu", "has_npu", col("has_npu", "TEXT", ["npu", "ai chip", "neural engine"], groupable=True)),
    ("android_version", "android_version", col("android_version", "INTEGER", ["android", "os version"])),
    ("purchased_year", "purchased_year", col("purchased_year", "INTEGER", ["purchased", "bought", "purchase year", "age"])),
], aliases=["devices", "device", "phones", "phone", "handsets", "handset", "company phones", "mobiles", "hardware", "fleet"])

# ============================== ACCOUNTS ==============================
fin = read("financials_quarterly.csv")
def lakh_col(src, new, aliases):
    return (new, (lambda r, s=src: inr(r[s])), col(new, "INTEGER", aliases))
add_table("financials", fin, [
    ("fiscal_year", "fiscal_year", col("fiscal_year", "TEXT", ["fy", "year", "years"], groupable=True)),
    ("fiscal_quarter", "fiscal_quarter", col("fiscal_quarter", "TEXT", ["quarter", "quarters", "q"], groupable=True)),
    lakh_col("revenue_inr_lakh", "total_revenue", ["total revenue", "quarterly revenue", "top line", "topline"]),
    lakh_col("material_cost_inr_lakh", "material_cost", ["materials", "material cost", "cogs", "cost of goods", "raw material"]),
    ("gross_margin_pct", "gross_margin_pct", col("gross_margin_pct", "REAL", ["gross margin", "gm", "gm percent", "gross margin percent"])),
    lakh_col("employee_cost_inr_lakh", "employee_cost", ["employee cost", "staff cost", "people cost", "wage bill", "manpower cost"]),
    lakh_col("selling_promotion_inr_lakh", "selling_promotion", ["selling", "promotion", "marketing spend", "selling and promotion", "s and p"]),
    lakh_col("other_expenses_inr_lakh", "other_expenses", ["other expenses", "overheads", "opex"]),
    lakh_col("rnd_inr_lakh", "rnd", ["r and d", "research", "research and development", "rd spend"]),
    lakh_col("one_off_items_inr_lakh", "one_off_items", ["one off", "one offs", "exceptional items", "one time costs"]),
    lakh_col("ebitda_inr_lakh", "ebitda", ["operating profit", "ebidta"]),
    ("ebitda_margin_pct", "ebitda_margin_pct", col("ebitda_margin_pct", "REAL", ["ebitda margin", "operating margin", "margin"])),
    lakh_col("dep_amort_inr_lakh", "dep_amort", ["depreciation", "amortisation", "d and a"]),
    lakh_col("finance_cost_inr_lakh", "finance_cost", ["interest", "finance costs", "borrowing cost"]),
    lakh_col("pbt_inr_lakh", "pbt", ["profit before tax", "pre tax profit"]),
    lakh_col("tax_inr_lakh", "tax", ["taxes", "income tax"]),
    lakh_col("pat_inr_lakh", "pat", ["profit", "net profit", "profit after tax", "bottom line", "net income"]),
    ("pat_margin_pct", "pat_margin_pct", col("pat_margin_pct", "REAL", ["pat margin", "net margin", "profit margin"])),
], aliases=["financials", "financial", "p and l", "pnl", "profit and loss", "quarterly results", "results", "accounts", "ebitda", "profit"])

rec = read("receivables_quarterly.csv")
add_table("receivables", rec, [
    ("fiscal_year", "fiscal_year", col("fiscal_year", "TEXT", ["fy", "year", "years"], groupable=True)),
    ("fiscal_quarter", "fiscal_quarter", col("fiscal_quarter", "TEXT", ["quarter", "quarters"], groupable=True)),
    ("branch", "branch", col("branch", "TEXT", ["branches", "region", "regions", "office"], groupable=True)),
    lakh_col("net_sales_inr_lakh", "quarter_sales", ["quarter sales", "billed in the quarter"]),
    lakh_col("closing_receivables_inr_lakh", "closing_receivables", ["receivables", "receivable", "outstanding", "debtors", "money owed", "dues", "collections due", "unpaid"]),
    ("dso_days", "dso_days", col("dso_days", "INTEGER", ["dso", "collection days", "days to collect", "days sales outstanding", "collection period", "payment delay"])),
], aliases=["receivables", "receivable", "debtors", "collections", "dso", "outstanding", "money owed", "collection days"])

bva = read("budget_vs_actual.csv")
add_table("budget_vs_actual", bva, [
    ("fiscal_year", "fiscal_year", col("fiscal_year", "TEXT", ["fy", "year", "years"], groupable=True)),
    ("business_unit", "business_unit", col("business_unit", "TEXT", ["unit", "units", "business units", "branch", "branches", "division", "divisions"], groupable=True)),
    lakh_col("budget_inr_lakh", "budget", ["budgets", "target", "targets", "plan", "budgeted"]),
    lakh_col("actual_inr_lakh", "actual", ["actuals", "achieved", "achievement", "delivered"]),
    ("attainment_pct", "attainment_pct", col("attainment_pct", "REAL", ["attainment", "attainment percent", "budget attainment", "achievement percent", "target achievement"])),
], aliases=["budget vs actual", "budget", "budgets", "attainment", "targets", "plan vs actual", "budget attainment"])

st = read("stockists_yearly.csv")
add_table("stockists", st, [
    ("fiscal_year", "fiscal_year", col("fiscal_year", "TEXT", ["fy", "year", "years"], groupable=True)),
    ("branch", "branch", col("branch", "TEXT", ["branches", "region", "regions", "office"], groupable=True)),
    ("stockist_id", "stockist_id", col("stockist_id", "TEXT", ["stockist id", "stockist code", "id", "code"], identifier=True)),
    ("town", "town", col("town", "TEXT", ["towns", "city", "cities", "territory", "territories", "location"], groupable=True)),
    lakh_col("net_sales_inr_lakh", "stockist_sales", ["stockist sales", "stockist revenue", "sales through stockist", "offtake", "purchases"]),
    ("avg_payment_days", "avg_payment_days", col("avg_payment_days", "INTEGER", ["payment days", "days to pay", "payment delay", "credit days", "payment period", "average payment days"])),
], aliases=["stockists", "stockist", "distributors", "distributor", "dealers", "dealer", "wholesalers", "trade partners"])

# ============================== DOCUMENTS ==============================
profile = read_json("company_profile.json")
dd = read_json("data_dictionary.json")
ak = read_json("answer_key_sales.json")
fy_cr = ak["net_sales_inr_cr"]


def md_table(header, rows):
    out = ["| " + " | ".join(header) + " |", "|" + "|".join(" --- " for _ in header) + "|"]
    for r in rows:
        out.append("| " + " | ".join(str(x) for x in r) + " |")
    return "\n".join(out)


snapshot = """# Kavira Pharmaceuticals: company snapshot

Kavira Pharmaceuticals Pvt Ltd is a fictional small enterprise with about INR 60 crore of revenue and %d employees. Its headquarters and plant are in Bengaluru, with branches in Bengaluru, Chennai and Kochi. It sells %d brands and also does government tender supply and contract manufacturing for other companies. The company was founded in %d and holds %d company-issued Android phones.

## Net sales by fiscal year

%s

The Indian fiscal year runs from April to March and is named by the year it ends in: FY2024 is April 2023 to March 2024. FY2023 is a baseline year only.

## Branches

%s

## Business events

%s

## How to read the data

Sales figures are net sales in rupees after discounts. Units are packs sold; for contract manufacturing they are thousands of tablets. Segments are %s. Contract manufacturing is billed from the Bengaluru plant. There is no data after March 2026, no Hyderabad branch yet, no competitor data and no former employees.
""" % (
    profile["employees"], 16, profile["founded"], profile["available_devices"]["total"],
    md_table(["Fiscal year", "Net sales", "Growth", "What happened"], [
        ("FY2023 (baseline)", "INR %.1f Cr" % fy_cr["FY2023"], "-", "Two branches"),
        ("FY2024", "INR %.1f Cr" % fy_cr["FY2024"], "%+.1f %%" % ak["yoy_growth_pct"]["FY2024"], "Pantowin price cap, lost contract client A, Chennai flooding; Kochi opens"),
        ("FY2025", "INR %.1f Cr" % fy_cr["FY2025"], "%+.1f %%" % ak["yoy_growth_pct"]["FY2025"], "Tamil Nadu tender, Vildora M launch, Kochi ramps up"),
        ("FY2026", "INR %.1f Cr" % fy_cr["FY2026"], "%+.1f %%" % ak["yoy_growth_pct"]["FY2026"], "Acneclear launch, new contract client C, Kochi at full strength, despite a metformin shortage"),
    ]),
    md_table(["Branch", "City", "State", "Opened", "Role"], [(b["branch"], b["city"], b["state_covered"], b["opened"], b["role"]) for b in branches]),
    "\n\n".join("**%s, %s (%s).** %s" % (e["month"], e["title"], e["type"].lower(), e["description"]) for e in events),
    ", ".join(dd["segments"]),
)
with open(os.path.join(SEED, "kavira_company_snapshot.md"), "w", encoding="utf-8", newline="\n") as f:
    f.write(snapshot)

policy = read_json("access_policy.json")
roles_txt = "\n".join("- **%s**: individual records %s; aggregates %s." % (r, v["individual"], v["aggregate"]) for r, v in policy["roles"].items())
agg = policy["aggregate_rules"]
access_md = """# Kavira HR data: who may see what

The default is deny. Salary fields are %s.

## Roles

%s

## Aggregate rules

- The minimum group size for any average, count or sum is %d people.
- Allowed functions: %s. Blocked functions: %s, because a maximum or a ranked list is always one person's salary.
- Averages are rounded to the nearest INR %d.
- A query that names or excludes an individual is blocked, because comparing it with the full average would reveal that person's salary.
- A group that differs from a recent query by fewer than %d people is blocked for the same reason.

## Execution

%s
""" % (
    ", ".join(policy["salary_fields"]), roles_txt, agg["min_group_size"], ", ".join(agg["allowed_functions"]), ", ".join(agg["blocked_functions"]),
    agg["round_averages_to_inr"], agg["block_if_group_differs_from_a_recent_query_by_fewer_than"], policy["execution"],
)
with open(os.path.join(SEED, "kavira_hr_access_policy.md"), "w", encoding="utf-8", newline="\n") as f:
    f.write(access_md)

# ============================== MANIFEST ==============================
manifest_path = os.path.join(SEED, "seed_manifest.json")
with open(manifest_path, encoding="utf-8") as f:
    old = json.load(f, object_pairs_hook=OrderedDict)
legacy_tables = OrderedDict((k, old["tables"][k]) for k in ("employees", "sales", "invoices") if k in old.get("tables", {}))
legacy_docs = OrderedDict((k, old["docs"][k]) for k in ("onboarding_checklist.docx", "q3_review.pptx", "leave_policy.pdf") if k in old.get("docs", {}))

roles = OrderedDict([
    ("HR", OrderedDict(tables=["staff", "compensation", "salary_history", "devices"], docs=["onboarding_checklist.docx", "kavira_hr_access_policy.md"])),
    ("SALES", OrderedDict(tables=["sales_monthly", "products", "branch_master", "sales_force", "events", "stockists"], docs=["kavira_company_snapshot.md"])),
    ("ACCOUNTS", OrderedDict(tables=["financials", "receivables", "budget_vs_actual"], docs=[])),
])
docs = OrderedDict(legacy_docs)
for name, (units, chunks) in {"onboarding_checklist.docx": (40, 8), "q3_review.pptx": (14, 14), "leave_policy.pdf": (6, 12)}.items():
    if name in docs:
        docs[name]["units"] = units
        docs[name]["chunks"] = chunks
docs["kavira_company_snapshot.md"] = OrderedDict(file="kavira_company_snapshot.md", kind="md", role="SALES", seeded=True, unitLabel="paragraph", confidential=False,
                                                 units=len([p for p in snapshot.split("\n\n") if p.strip()]))
docs["kavira_hr_access_policy.md"] = OrderedDict(file="kavira_hr_access_policy.md", kind="md", role="HR", seeded=True, unitLabel="paragraph", confidential=False,
                                                 units=len([p for p in access_md.split("\n\n") if p.strip()]))
all_tables = OrderedDict(tables)
all_tables.update(legacy_tables)
manifest = OrderedDict([
    ("version", 2),
    ("today", old.get("today", "2026-10-10")),
    ("company", OrderedDict(name=profile["name"], fictional=True, hq=profile["hq"], founded=profile["founded"], employees=profile["employees"],
                            phones=profile["available_devices"]["total"], fiscal_year_note=profile["fiscal_year_note"],
                            generator="tools/kavira/make_kavira_data.py + export_seed.py")),
    ("notes", [
        "Role names match core NodeRole: HR, SALES, ACCOUNTS; each role is one phone of the demo.",
        "Kavira Pharmaceuticals (fictional): money columns are INR (the generator's lakh figures x 100,000); fiscal years run April to March and are named by the year they end in.",
        "sales_monthly.net_sales answers to 'sales' and 'revenue' so the deck prompt binds its measure; no other table carries those aliases.",
        "compensation and salary_history carry the confidential pay columns: only AVG/COUNT leave the HR phone, with the 5-person floor; staff is the pay-free directory.",
        "The legacy tour tables (employees, sales, invoices) stay listed for the planner tests; no role loads them.",
        "Seed rows carry _sources.seed = 1 and _docs.seed = 1.",
    ]),
    ("roles", roles),
    ("tables", all_tables),
    ("docs", docs),
])
with open(manifest_path, "w", encoding="utf-8", newline="\n") as f:
    json.dump(manifest, f, indent=2, ensure_ascii=False)
    f.write("\n")

# ============================== REFERENCE OUTPUT ==============================
for name in ("answer_key_sales.json", "answer_key_hr.json", "access_policy.json", "app_users.csv", "company_profile.json",
             "brand_guidelines.json", "image_prompt_bank.json", "data_dictionary.json"):
    shutil.copy(os.path.join(SRC, name), os.path.join(OUT, name))
os.makedirs(os.path.join(OUT, "hr_shards"), exist_ok=True)
for name in os.listdir(os.path.join(SRC, "hr_shards")):
    shutil.copy(os.path.join(SRC, "hr_shards", name), os.path.join(OUT, "hr_shards", name))

# ============================== FIGURES FOR THE SCRIPTED DEMO ==============================
fy_net = defaultdict(int)
fy_br = defaultdict(int)
fy_br_month = defaultdict(int)
for r in sales:
    v = int(inr(r["net_sales_inr_lakh"]))
    fy_net[r["fiscal_year"]] += v
    fy_br[(r["fiscal_year"], r["branch"])] += v
    fy_br_month[(r["fiscal_year"], r["branch"], r["month"])] += v
reps = defaultdict(int)
for e in emps:
    if "Medical Representative" in e["designation"]:
        reps[e["branch"]] += 1
print("tables:", json.dumps(counts))
print("net sales INR by FY:", dict(fy_net))
print("FY2026 net sales by branch:", {b: fy_br[("FY2026", b)] for b in ("BLR", "CHN", "KOC")})
print("medical reps by branch:", dict(reps))
print("FY2026 sales per rep by branch:", {b: round(fy_br[("FY2026", b)] / reps[b]) for b in ("BLR", "CHN", "KOC")})
q4 = [m for m in ("2026-01", "2026-02", "2026-03")]
print("FY2026 Q4 months by branch:", {b: [fy_br_month[("FY2026", b, m)] for m in q4] for b in ("BLR", "CHN", "KOC")})
inc = defaultdict(list)
for e in comp_rows:
    if "Medical Representative" in e["designation"]:
        inc[e["branch"]].append(int(e["incentive_paid_fy2026_inr"]))
print("avg MR incentive by branch:", {b: round(sum(v) / len(v)) for b, v in inc.items()}, "company:", round(sum(sum(v) for v in inc.values()) / sum(len(v) for v in inc.values())))
print("avg MR ctc by branch:", read_json("answer_key_hr.json")["avg_mr_ctc_by_branch_inr"])
