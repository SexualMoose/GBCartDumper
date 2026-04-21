"""
Attempts a CONFIG+RROM for just bank 0 and prints whatever data packets
the flasher sends back. Use this to see if the cart address/data bus is
producing any real bytes at all.

    python probe_pc_rrom.py COM8 185000
"""
from __future__ import annotations

import sys
import time

import serial
from serial.tools import list_ports

PACKET_SIZE = 72
FRAME_SIZE = 64
DATA, ACK, NAK, END = 0x55, 0xAA, 0xF0, 0x0F
CMD_CONFIG = 0x00
DATA_NORMAL, DATA_LAST = 0x01, 0x02
OP_RROM = 0x00
MBC_ROMONLY = 0x04
ALG_16 = 0x00


def crc16_ccitt(data: bytes) -> int:
    crc = 0
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if (crc & 0x8000) else (crc << 1) & 0xFFFF
    return crc


def build_rrom(pages: int, mbc: int) -> bytes:
    p = bytearray(PACKET_SIZE)
    p[0] = DATA
    p[1] = CMD_CONFIG
    p[2] = OP_RROM
    p[3] = mbc
    p[4] = ALG_16
    p[5] = 0
    pm1 = pages - 1
    p[6] = (pm1 >> 8) & 0xFF
    p[7] = pm1 & 0xFF
    c = crc16_ccitt(bytes(p[:PACKET_SIZE - 2]))
    p[PACKET_SIZE - 2] = (c >> 8) & 0xFF
    p[PACKET_SIZE - 1] = c & 0xFF
    return bytes(p)


def pick_port():
    for p in list_ports.comports():
        if p.vid == 0x0403 and p.pid == 0x6001:
            return p.device
    return None


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else pick_port()
    baud = int(sys.argv[2]) if len(sys.argv) > 2 else 185000
    mbc = int(sys.argv[3], 0) if len(sys.argv) > 3 else MBC_ROMONLY
    print(f"Opening {port} @ {baud} with MBC=0x{mbc:02X} ...")

    with serial.Serial(port, baudrate=baud, bytesize=8, parity="N", stopbits=1,
                       timeout=3.0, write_timeout=2.0) as s:
        s.setDTR(False); s.setRTS(False)
        s.reset_input_buffer(); s.reset_output_buffer()
        time.sleep(0.2)

        # Try 2 pages (32 KiB) — a single ROM bank 0 + bank 1 for ROMONLY carts.
        req = build_rrom(2, mbc)
        print("TX CONFIG:", req.hex(" "))
        s.write(req); s.flush()

        got_packets = 0
        nonzero_bytes = 0
        for _ in range(2 * 256 + 4):  # 2 pages × 256 frames + slop
            b = s.read(1)
            if not b:
                print("  timeout — nothing more to read")
                break
            if b[0] == NAK:
                print("  device NAK'd")
                break
            if b[0] == END:
                print("  device sent END")
                break
            if b[0] != DATA:
                print(f"  stray 0x{b[0]:02X}, skipping")
                continue
            rest = s.read(PACKET_SIZE - 1)
            pkt = bytes([DATA]) + rest
            want = (pkt[-2] << 8) | pkt[-1]
            got = crc16_ccitt(pkt[:-2])
            if want != got:
                print(f"  pkt#{got_packets}: CRC FAIL want=0x{want:04X} got=0x{got:04X}")
                s.write(bytes([NAK]))
                continue
            data_type = pkt[1]
            pkt_num = pkt[3]
            page_hi, page_lo = pkt[4], pkt[5]
            frame = pkt[6:6 + FRAME_SIZE]
            nz = sum(1 for x in frame if x != 0)
            nonzero_bytes += nz
            if got_packets < 3 or nz > 0 and got_packets < 10:
                print(f"  pkt#{got_packets} type={data_type} pktnum={pkt_num} page={page_hi:02X}{page_lo:02X} "
                      f"nz={nz}/64: {frame[:16].hex(' ')} ...")
            got_packets += 1
            s.write(bytes([ACK]))
            if data_type == DATA_LAST:
                print(f"  end of transfer (LAST), {got_packets} packets total")
                break
        print(f"Total data packets: {got_packets}, non-zero bytes: {nonzero_bytes}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
