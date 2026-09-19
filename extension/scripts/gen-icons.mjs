// 아이콘 자산 생성기. 외부 이미지 다운로드 없이 zlib(Node 내장)만으로 단색 원형 PNG 를 만든다.
// 디자인 완성도는 목표가 아니다 — manifest.icons / notifications 참조가 실기동에서
// 404 로 깨지지 않는 것이 목표다(실제 아이콘은 디자인 확정 후 교체).
import { deflateSync, crc32 } from "node:zlib";
import { writeFileSync, mkdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const OUT_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "public", "icons");
mkdirSync(OUT_DIR, { recursive: true });

// 브랜드 컬러(사이드패널 primary 버튼과 동일 계열) 원형, 배경 투명.
const RGBA = [0x1a, 0x73, 0xe8, 0xff];

function chunk(type, data) {
  const typeBuf = Buffer.from(type, "ascii");
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const crcInput = Buffer.concat([typeBuf, data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(crcInput) >>> 0, 0);
  return Buffer.concat([len, typeBuf, data, crc]);
}

function makePng(size) {
  const r = size / 2;
  const cx = r, cy = r;
  const rows = [];
  for (let y = 0; y < size; y++) {
    const row = Buffer.alloc(1 + size * 4);
    row[0] = 0; // filter: none
    for (let x = 0; x < size; x++) {
      const dx = x + 0.5 - cx;
      const dy = y + 0.5 - cy;
      const inside = dx * dx + dy * dy <= r * r;
      const o = 1 + x * 4;
      if (inside) {
        row[o] = RGBA[0]; row[o + 1] = RGBA[1]; row[o + 2] = RGBA[2]; row[o + 3] = RGBA[3];
      } // else stays 0,0,0,0 (투명)
    }
    rows.push(row);
  }
  const raw = Buffer.concat(rows);
  const idatData = deflateSync(raw);

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // color type: RGBA
  ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;

  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  return Buffer.concat([
    signature,
    chunk("IHDR", ihdr),
    chunk("IDAT", idatData),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

for (const size of [16, 32, 48, 128]) {
  const out = path.join(OUT_DIR, `icon${size}.png`);
  writeFileSync(out, makePng(size));
  console.log("생성:", out);
}
