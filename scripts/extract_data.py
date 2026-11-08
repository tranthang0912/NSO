#!/usr/bin/env python3
"""
Extractor cho Ninja School Online (Web Edition)
Chuyển đổi dữ liệu tĩnh từ ../SRC NSOACE FIX/nsoz.sql và Data/
sang seed PostgreSQL chuẩn + bộ tài nguyên tĩnh (zoom level 2) + 177 map JSON.
"""

import json
import os
import re
import shutil
import struct
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT_DIR = Path(__file__).resolve().parent.parent
SRC_DIR = ROOT_DIR.parent / "SRC NSOACE FIX"
SQL_PATH = SRC_DIR / "nsoz.sql"
DATA_SRC = SRC_DIR / "Data"

OUT_DATA_DIR = ROOT_DIR / "data"
SEED_SQL_PATH = OUT_DATA_DIR / "seed_static.sql"
ASSETS_DIR = OUT_DATA_DIR / "assets"
LANG_DIR = OUT_DATA_DIR / "lang"

# Bitflags chuẩn từ TileMap.java
T_EMPTY = 0
T_TOP = 2
T_LEFT = 2 << 1
T_RIGHT = 2 << 2
T_TREE = 2 << 3
T_WATERFALL = 2 << 4
T_WATERFLOW = 2 << 5
T_TOPFALL = 2 << 6
T_OUTSIDE = 2 << 7
T_DOWN1PIXEL = 2 << 8
T_BRIDGE = 2 << 9
T_UNDERWATER = 2 << 10
T_SOLIDGROUND = 2 << 11
T_BOTTOM = 2 << 12
T_DIE = 2 << 13
T_HEBI = 2 << 14
T_BANG = 2 << 15
T_JUM8 = 2 << 16
T_NT0 = 2 << 17
T_NT1 = 2 << 18
T_RIVERFLOW = 2 << 19

STATIC_TABLES = [
    "clazz",
    "map",
    "monster",
    "npc",
    "item",
    "item_option",
    "skill_template",
    "skill",
    "skill_option",
    "nj_part",
    "nj_skill",
    "nj_arrow",
    "nj_effect",
    "nj_image",
    "effect",
    "effect_data",
    "effect_data_auto",
    "stores",
    "store_data",
    "weapon_store",
    "task",
    "task_template",
    "others",
]


def parse_sql_inserts(sql_text: str, target_tables: list[str]) -> dict[str, list[list]]:
    """State-machine SQL INSERT parser xử lý chuẩn escape quote, JSON và nhiều block INSERT."""
    results = {t: [] for t in target_tables}
    n = len(sql_text)
    pos = 0
    while True:
        idx = sql_text.find("INSERT INTO `", pos)
        if idx == -1:
            break
        end_tbl = sql_text.find("`", idx + 13)
        if end_tbl == -1:
            break
        tbl = sql_text[idx + 13 : end_tbl]
        if tbl not in results:
            pos = end_tbl + 1
            continue

        val_kw = sql_text.find("VALUES", end_tbl)
        if val_kw == -1:
            pos = end_tbl + 1
            continue

        i = val_kw + 6
        while i < n:
            ch = sql_text[i]
            if ch == ";":
                i += 1
                break
            if ch == "(":
                i += 1
                row = []
                while i < n:
                    while i < n and sql_text[i] in " \t\r\n":
                        i += 1
                    if i >= n:
                        break
                    c = sql_text[i]
                    if c == "'":
                        i += 1
                        buf = []
                        while i < n:
                            sc = sql_text[i]
                            if sc == "\\":
                                i += 1
                                if i < n:
                                    esc = sql_text[i]
                                    if esc == "n":
                                        buf.append("\n")
                                    elif esc == "r":
                                        buf.append("\r")
                                    elif esc == "t":
                                        buf.append("\t")
                                    elif esc == "0":
                                        buf.append("\0")
                                    else:
                                        buf.append(esc)
                                    i += 1
                            elif sc == "'":
                                if i + 1 < n and sql_text[i + 1] == "'":
                                    buf.append("'")
                                    i += 2
                                else:
                                    i += 1
                                    break
                            else:
                                buf.append(sc)
                                i += 1
                        row.append("".join(buf))
                    else:
                        start = i
                        while i < n and sql_text[i] not in ",)":
                            i += 1
                        tok = sql_text[start:i].strip()
                        if tok.upper() == "NULL":
                            row.append(None)
                        else:
                            try:
                                row.append(float(tok) if "." in tok else int(tok))
                            except ValueError:
                                row.append(tok)
                    while i < n and sql_text[i] in " \t\r\n":
                        i += 1
                    if i < n and sql_text[i] == ",":
                        i += 1
                        continue
                    if i < n and sql_text[i] == ")":
                        i += 1
                        break
                results[tbl].append(row)
            else:
                i += 1
        pos = i
    return results


def pg_literal(val, is_bool: bool = False) -> str:
    """Format Python value thành literal PostgreSQL thuần (không dùng backslash escape MySQL)."""
    if val is None:
        return "NULL"
    if is_bool:
        return "TRUE" if bool(val) else "FALSE"
    if isinstance(val, bool):
        return "TRUE" if val else "FALSE"
    if isinstance(val, (int, float)):
        return str(val)
    s = str(val).replace("\r\n", "\n").replace("\r", "\n").replace("\0", "")
    if "\n" in s:
        parts = s.split("\n")
        return " || chr(10) || ".join("'" + p.replace("'", "''") + "'" for p in parts)
    return "'" + s.replace("'", "''") + "'"


def normalize_json_str(raw: str, default: str = "[]") -> str:
    if raw is None or str(raw).strip() == "":
        return default
    parsed = json.loads(raw)
    return json.dumps(parsed, ensure_ascii=False, separators=(",", ":"))


def compute_tile_types(tmw: int, tmh: int, tile_id: int, tiles: list[int], location_stand: list[list[int]]) -> list[int]:
    """Tính collision bitflags chuẩn 100% theo TileMap.loadMap() & locationStand."""
    length = tmw * tmh
    types = [0] * length
    stands = set()
    for xy in location_stand:
        if len(xy) >= 2:
            col, row = int(xy[0]), int(xy[1])
            k = row * tmw + col
            if 0 <= k < length:
                stands.add(k)

    for i in range(length):
        if i in stands:
            types[i] |= T_TOP
        m = tiles[i]
        if tile_id == 4:
            if m in (1, 2, 3, 4, 5, 6, 9, 10, 79, 80, 13, 14, 43, 44, 45, 50):
                types[i] |= T_TOP
            if m in (9, 11):
                types[i] |= T_LEFT
            if m in (10, 12):
                types[i] |= T_RIGHT
            if m in (13, 14):
                types[i] |= T_BRIDGE
            if m in (76, 77):
                types[i] |= T_WATERFLOW
                if m == 78:
                    types[i] |= T_SOLIDGROUND
        elif tile_id == 1:
            if m in (1, 2, 3, 4, 5, 6, 7, 36, 37, 54, 91, 92, 93, 94, 73, 74, 97, 98, 116, 117, 118, 120, 61):
                types[i] |= T_TOP
            if m in (2, 3, 4, 5, 6, 20, 21, 22, 23, 36, 37, 38, 39, 61):
                types[i] |= T_SOLIDGROUND
            if m in (8, 9, 10, 12, 13, 14, 30):
                types[i] |= T_TREE
            if m == 17:
                types[i] |= T_WATERFALL
            if m == 18:
                types[i] |= T_TOPFALL
            if m in (37, 38, 61):
                types[i] |= T_LEFT
            if m in (36, 39, 61):
                types[i] |= T_RIGHT
            if m == 19:
                types[i] |= T_WATERFLOW
                if i >= tmw and (types[i - tmw] & T_SOLIDGROUND) == T_SOLIDGROUND:
                    types[i] |= T_SOLIDGROUND
            if m == 35:
                types[i] |= T_UNDERWATER
            if m == 7:
                types[i] |= T_BRIDGE
            if m in (32, 33, 34):
                types[i] |= T_OUTSIDE
        elif tile_id == 2:
            if m in (
                1, 2, 3, 4, 5, 6, 7, 36, 37, 54, 61, 73, 76, 77, 78, 79, 82, 83,
                98, 99, 100, 102, 103, 108, 109, 110, 112, 113, 116, 117,
                125, 126, 127, 129, 130,
            ):
                types[i] |= T_TOP
            if m in (
                1, 3, 4, 5, 6, 20, 21, 22, 23, 36, 37, 38, 39, 55,
                109, 111, 112, 113, 114, 115, 116, 127, 129, 130,
            ):
                types[i] |= T_SOLIDGROUND
            if m in (8, 9, 10, 12, 13, 14, 30, 135):
                types[i] |= T_TREE
            if m == 17:
                types[i] |= T_WATERFALL
            if m == 18:
                types[i] |= T_TOPFALL
            if m in (61, 37, 38, 127, 130, 131):
                types[i] |= T_LEFT
            if m in (61, 36, 39, 127, 129, 132):
                types[i] |= T_RIGHT
            if m in (19, 134):
                types[i] |= T_WATERFLOW
                if i >= tmw and (types[i - tmw] & T_SOLIDGROUND) == T_SOLIDGROUND:
                    types[i] |= T_SOLIDGROUND
            if m == 35:
                types[i] |= T_UNDERWATER
            if m == 7:
                types[i] |= T_BRIDGE
            if m in (32, 33, 34):
                types[i] |= T_OUTSIDE
            if m in (61, 127):
                types[i] |= T_BOTTOM
        elif tile_id == 3:
            if m in (
                1, 2, 3, 4, 5, 6, 7, 11, 14, 17, 43, 51, 63, 65, 67, 68, 71, 72,
                83, 84, 85, 87, 91, 94, 97, 98, 106, 107, 111, 113, 117, 118, 119,
                125, 126, 129, 130, 131, 133, 136, 138, 139, 142,
            ):
                types[i] |= T_TOP
            if m in (124, 116, 123, 44, 12, 15, 16, 45, 10, 9):
                types[i] |= T_SOLIDGROUND
            if m == 23:
                types[i] |= T_WATERFALL
            if m == 24:
                types[i] |= T_TOPFALL
            if m in (6, 15, 51, 95, 97, 106, 111, 123, 125, 138, 140):
                types[i] |= T_LEFT
            if m in (7, 16, 51, 96, 98, 107, 111, 124, 126, 139, 141):
                types[i] |= T_RIGHT
            if m == 25:
                types[i] |= T_WATERFLOW
                if i >= tmw and (types[i - tmw] & T_SOLIDGROUND) == T_SOLIDGROUND:
                    types[i] |= T_SOLIDGROUND
            if m == 34:
                types[i] |= T_UNDERWATER
            if m == 17:
                types[i] |= T_BRIDGE
            if m in (33, 103, 104, 105, 26):
                types[i] |= T_OUTSIDE
            if m in (51, 111, 68):
                types[i] |= T_BOTTOM
            if m in (82, 110, 143):
                types[i] |= T_DIE
            if m == 113:
                types[i] |= T_BANG
            if m == 142:
                types[i] |= T_HEBI
            if m in (40, 41):
                types[i] |= T_JUM8
            if m == 110:
                types[i] |= T_NT0
            if m == 143:
                types[i] |= T_NT1
    return types


def parse_binary_map(map_id: int) -> tuple[int, int, list[int]]:
    raw = (DATA_SRC / "Map" / str(map_id)).read_bytes()
    b0, b1 = struct.unpack("bb", raw[:2])
    idx = 0
    key = 0
    if b0 + b1 == 0:
        idx = 2
        key = b0
    tmw = raw[idx] - key
    tmh = raw[idx + 1] - key
    expected_len = 2 + idx + tmw * tmh
    if expected_len != len(raw):
        raise ValueError(f"Map {map_id}: expected {expected_len} bytes, got {len(raw)}")
    tiles = [raw[2 + idx + k] - key for k in range(tmw * tmh)]
    return tmw, tmh, tiles


def emit_batch_inserts(out, table: str, cols: list[str], rows: list[list], bool_cols: set[int] = None, batch_size: int = 80):
    if not rows:
        return
    bool_cols = bool_cols or set()
    col_sql = ", ".join(cols)
    for start in range(0, len(rows), batch_size):
        chunk = rows[start : start + batch_size]
        vals_sql = []
        for r in chunk:
            formatted = [pg_literal(v, idx in bool_cols) for idx, v in enumerate(r)]
            vals_sql.append("(" + ", ".join(formatted) + ")")
        out.write(f"INSERT INTO {table} ({col_sql}) VALUES\n")
        out.write(",\n".join(vals_sql))
        out.write("\nON CONFLICT DO NOTHING;\n")


def copy_clean_dir(src: Path, dst: Path):
    dst.mkdir(parents=True, exist_ok=True)
    count = 0
    for f in src.iterdir():
        if f.is_file():
            shutil.copy2(f, dst / f.name)
            count += 1
    return count


def main():
    if not SQL_PATH.exists():
        raise FileNotFoundError(f"Không tìm thấy file nguồn: {SQL_PATH}")

    print("[1/5] Đọc và parse nsoz.sql...")
    sql_text = SQL_PATH.read_text(encoding="utf-8", errors="replace")
    data = parse_sql_inserts(sql_text, STATIC_TABLES)

    for t in STATIC_TABLES:
        print(f"  - {t:18s}: {len(data[t])} rows")
        if len(data[t]) == 0:
            raise RuntimeError(f"Bảng {t} không có dữ liệu!")

    OUT_DATA_DIR.mkdir(parents=True, exist_ok=True)
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    LANG_DIR.mkdir(parents=True, exist_ok=True)

    print("[2/5] Chuẩn hóa JSON & sinh data/seed_static.sql (PostgreSQL thuần)...")
    # Chuẩn hóa từng bảng
    map_rows = []
    parsed_maps_meta = {}
    for r in data["map"]:
        mid = int(r[0])
        npcs = normalize_json_str(r[2], "[]")
        waypoints = normalize_json_str(r[3], "[]")
        monsters = normalize_json_str(r[4], "[]")
        zone_num = int(r[5])
        loc_stand = normalize_json_str(r[6], "[]")
        tile_id = int(r[7])
        bg_id = int(r[8])
        map_type = int(r[9])
        item_arr = normalize_json_str(r[10], "[]")
        behind = normalize_json_str(r[11], "[]")
        betwen = normalize_json_str(r[12], "[]")
        front = normalize_json_str(r[13], "[]")
        map_rows.append([mid, r[1], npcs, waypoints, monsters, zone_num, loc_stand, tile_id, bg_id, map_type, item_arr, behind, betwen, front])
        parsed_maps_meta[mid] = {
            "id": mid,
            "name": r[1],
            "npcs": json.loads(npcs),
            "waypoints": json.loads(waypoints),
            "monsters": json.loads(monsters),
            "zoneNumber": zone_num,
            "locationStand": json.loads(loc_stand),
            "tileId": tile_id,
            "bgId": bg_id,
            "type": map_type,
            "items": json.loads(item_arr),
            "behind": json.loads(behind),
            "betwen": json.loads(betwen),
            "front": json.loads(front),
        }

    monster_rows = []
    for r in data["monster"]:
        monster_rows.append([
            int(r[0]), r[1], int(r[2]), bool(int(r[3])), int(r[4]), int(r[5]),
            int(r[6]), int(r[7]), int(r[8]), int(r[9]),
            normalize_json_str(r[10], "[]"),
            normalize_json_str(r[11], "[]"),
            normalize_json_str(r[12], "[]"),
            normalize_json_str(r[13], "[]"),
            normalize_json_str(r[14], "[]"),
            normalize_json_str(r[15], "[]"),
            normalize_json_str(r[16], "[]"),
        ])

    npc_rows = [[int(r[0]), r[1], int(r[2]), int(r[3]), int(r[4]), normalize_json_str(r[5], "[]")] for r in data["npc"]]
    item_rows = [[int(r[0]), r[1], int(r[2]), int(r[3]), r[4], int(r[5]), int(r[6]), int(r[7]), int(r[8]), bool(int(r[9]))] for r in data["item"]]
    item_opt_rows = [[int(r[0]), int(r[1]), r[2]] for r in data["item_option"]]
    skill_tpl_rows = [[int(r[0]), int(r[1]), r[2], int(r[3]), int(r[4]), int(r[5]), r[6]] for r in data["skill_template"]]
    skill_rows = [[int(r[0]), int(r[1]), int(r[2]), int(r[3]), int(r[4]), int(r[5]), int(r[6]), int(r[7]), int(r[8]), normalize_json_str(r[9], "[]")] for r in data["skill"]]
    skill_opt_rows = [[int(r[0]), r[1]] for r in data["skill_option"]]
    nj_part_rows = [[int(r[0]), int(r[1]), normalize_json_str(r[2], "[]")] for r in data["nj_part"]]
    nj_skill_rows = [[int(r[0]), int(r[1]), int(r[2]), int(r[3]), normalize_json_str(r[4], "[]"), normalize_json_str(r[5], "[]")] for r in data["nj_skill"]]
    nj_arrow_rows = [[int(r[0]), normalize_json_str(r[1], "[]")] for r in data["nj_arrow"]]
    nj_effect_rows = [[int(r[0]), normalize_json_str(r[1], "[]")] for r in data["nj_effect"]]
    nj_image_rows = [[int(r[0]), normalize_json_str(r[1], "{}")] for r in data["nj_image"]]
    effect_rows = [[int(r[0]), r[1], int(r[2]), int(r[3])] for r in data["effect"]]
    effect_data_rows = [[int(r[0]), normalize_json_str(r[1], "[]"), normalize_json_str(r[2], "[]"), normalize_json_str(r[3], "[]"), normalize_json_str(r[4], "[]")] for r in data["effect_data"]]
    effect_auto_rows = [[int(r[0]), normalize_json_str(r[1], "[]"), normalize_json_str(r[2], "[]"), normalize_json_str(r[3], "[]")] for r in data["effect_data_auto"]]
    stores_rows = [[int(r[0]), r[1]] for r in data["stores"]]
    store_data_rows = [[int(r[0]), int(r[1]), int(r[2]), int(r[3]), bool(int(r[4])), int(r[5]), int(r[6]), int(r[7]), int(r[8]), normalize_json_str(r[9], "[]")] for r in data["store_data"]]
    weapon_store_rows = [[int(r[0]), int(r[1]), int(r[2]), int(r[3]), int(r[4]), int(r[5]), int(r[6]), normalize_json_str(r[7], "[]")] for r in data["weapon_store"]]
    task_rows = [[int(r[0]), normalize_json_str(r[1], "[]"), normalize_json_str(r[2], "[]")] for r in data["task"]]
    task_tpl_rows = [[int(r[0]), r[1], r[2], normalize_json_str(r[3], "[]"), normalize_json_str(r[4], "[]"), int(r[5]), normalize_json_str(r[6], "[]"), normalize_json_str(r[7], "[]")] for r in data["task_template"]]
    others_rows = [[int(r[0]), r[1], normalize_json_str(r[2], "[]")] for r in data["others"]]

    with SEED_SQL_PATH.open("w", encoding="utf-8", newline="\n") as out:
        out.write("-- Ninja School Online (Web Edition) - Static Seed Data (PostgreSQL)\n")
        out.write("BEGIN;\n\n")
        emit_batch_inserts(out, "clazz", ["id", "name"], data["clazz"])
        emit_batch_inserts(out, "map", ["id", "name", "npc", "waypoint", "monster", "zone_number", "location_stand", "tile_id", "bg_id", "map_type", "item", "behind", "betwen", "front"], map_rows, batch_size=25)
        emit_batch_inserts(out, "monster", ["id", "name", "level", "boss", "type", "hp", "range_move", "speed", "type_fly", "n_img", "move", "attack", "sprites", "frames", "sequence", "frame_char", "index_splash"], monster_rows, bool_cols={3}, batch_size=30)
        emit_batch_inserts(out, "npc", ["id", "name", "head", "body", "leg", "menu"], npc_rows)
        emit_batch_inserts(out, "item", ["id", "name", "type", "gender", "description", "level", "icon", "part", "fashion", "is_up_to_up"], item_rows, bool_cols={9})
        emit_batch_inserts(out, "item_option", ["id", "type", "name"], item_opt_rows)
        emit_batch_inserts(out, "skill_template", ["id", "class_id", "name", "max_point", "type", "icon", "description"], skill_tpl_rows)
        emit_batch_inserts(out, "skill", ["id", "template_id", "max_fight", "level", "mana_use", "cooldown", "point", "dx", "dy", "options"], skill_rows)
        emit_batch_inserts(out, "skill_option", ["id", "name"], skill_opt_rows)
        emit_batch_inserts(out, "nj_part", ["id", "type", "part"], nj_part_rows)
        emit_batch_inserts(out, "nj_skill", ["id", "skill_id", "eff_id", "num_eff", "skill_stand", "skill_fly"], nj_skill_rows, batch_size=25)
        emit_batch_inserts(out, "nj_arrow", ["id", "img_id"], nj_arrow_rows)
        emit_batch_inserts(out, "nj_effect", ["id", "info"], nj_effect_rows)
        emit_batch_inserts(out, "nj_image", ["id", "small_image"], nj_image_rows, batch_size=150)
        emit_batch_inserts(out, "effect", ["id", "name", "type", "icon"], effect_rows)
        emit_batch_inserts(out, "effect_data", ["id", "sprites", "frames", "running", "frame_char"], effect_data_rows, batch_size=20)
        emit_batch_inserts(out, "effect_data_auto", ["id", "sprites", "frames", "running"], effect_auto_rows, batch_size=10)
        emit_batch_inserts(out, "stores", ["id", "name"], stores_rows)
        emit_batch_inserts(out, "store_data", ["id", "item_id", "sys", "store", "is_locked", "coin", "gold", "yen", "expire", "options"], store_data_rows, bool_cols={4})
        emit_batch_inserts(out, "weapon_store", ["id", "template_id", "sys", "xu", "luong", "yen", "expire", "options"], weapon_store_rows)
        emit_batch_inserts(out, "task", ["id", "npcs", "maps"], task_rows)
        emit_batch_inserts(out, "task_template", ["task_id", "name", "detail", "subnames", "counts", "level_require", "kill_mob", "pick_item"], task_tpl_rows)
        emit_batch_inserts(out, "others", ["id", "name", "val"], others_rows, batch_size=5)
        out.write("\nCOMMIT;\n")

    print(f"  -> Đã tạo {SEED_SQL_PATH} ({SEED_SQL_PATH.stat().st_size / 1024:.1f} KB)")

    print("[3/5] Parse 177 map nhị phân Data/Map -> data/assets/maps/{id}.json...")
    maps_out_dir = ASSETS_DIR / "maps"
    maps_out_dir.mkdir(parents=True, exist_ok=True)
    for mid, meta in parsed_maps_meta.items():
        tmw, tmh, tiles = parse_binary_map(mid)
        types = compute_tile_types(tmw, tmh, meta["tileId"], tiles, meta["locationStand"])
        map_json = {
            "id": mid,
            "name": meta["name"],
            "tmw": tmw,
            "tmh": tmh,
            "pxw": tmw * 24,
            "pxh": tmh * 24,
            "tileId": meta["tileId"],
            "bgId": meta["bgId"],
            "type": meta["type"],
            "tiles": tiles,
            "types": types,
            "npcs": meta["npcs"],
            "waypoints": meta["waypoints"],
            "behind": meta["behind"],
            "betwen": meta["betwen"],
            "front": meta["front"],
        }
        (maps_out_dir / f"{mid}.json").write_text(
            json.dumps(map_json, ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )
    print(f"  -> Đã xuất {len(parsed_maps_meta)} file JSON bản đồ.")

    print("[4/5] Sao chép & chuẩn hóa tài nguyên ảnh (mức zoom 2) + Lang...")
    small_dst = ASSETS_DIR / "img" / "small"
    small_dst.mkdir(parents=True, exist_ok=True)
    small_src = DATA_SRC / "Img" / "Small" / "2"
    # Ưu tiên Small{id}.png chuẩn nếu có tên trùng
    small_candidates: dict[int, tuple[int, Path]] = {}
    for f in small_src.iterdir():
        if not f.is_file():
            continue
        m = re.match(r"^(Small)?(\d+)(png)?\.png$", f.name, re.I)
        if not m:
            continue
        sid = int(m.group(2))
        score = 0 if f.name == f"Small{sid}.png" else (1 if f.name.lower() == f"small{sid}.png" else 2)
        if sid not in small_candidates or score < small_candidates[sid][0]:
            small_candidates[sid] = (score, f)
    for sid, (_, src_file) in small_candidates.items():
        shutil.copy2(src_file, small_dst / f"{sid}.png")

    c_mob = copy_clean_dir(DATA_SRC / "Img" / "Mob" / "2", ASSETS_DIR / "img" / "mob")
    c_eff = copy_clean_dir(DATA_SRC / "Img" / "Effect" / "2", ASSETS_DIR / "img" / "effect")
    c_effa = copy_clean_dir(DATA_SRC / "Img" / "EffectAuto" / "2", ASSETS_DIR / "img" / "effect_auto")
    c_tile = copy_clean_dir(DATA_SRC / "Img" / "Map" / "2" / "tile", ASSETS_DIR / "img" / "map" / "tile")
    c_bg = copy_clean_dir(DATA_SRC / "Img" / "Map" / "2" / "bg", ASSETS_DIR / "img" / "map" / "bg")
    c_obj = copy_clean_dir(DATA_SRC / "Img" / "Object" / "2", ASSETS_DIR / "img" / "object")
    c_lang = copy_clean_dir(DATA_SRC / "Lang", LANG_DIR)

    print(
        f"  -> Small: {len(small_candidates)}, Mob: {c_mob}, Effect: {c_eff}, "
        f"EffectAuto: {c_effa}, Tile: {c_tile}, Bg: {c_bg}, Object: {c_obj}, Lang: {c_lang}"
    )

    print("[5/5] Kiểm tra toàn vẹn tài nguyên...")
    assert len(parsed_maps_meta) == 177, f"Expected 177 maps, got {len(parsed_maps_meta)}"
    assert len(small_candidates) >= 4200, "Missing Small assets"
    # Kiểm tra các item icon chính tồn tại trong Small
    missing_icons = []
    for r in item_rows:
        icon = r[6]
        if icon > 0 and icon not in small_candidates:
            missing_icons.append((r[0], r[1], icon))
    print(f"  -> Item icons missing from Small/2: {len(missing_icons)} (legacy icons in DB: {missing_icons[:5]})")
    print("Hoàn tất trích xuất Bước 1!")


if __name__ == "__main__":
    main()
