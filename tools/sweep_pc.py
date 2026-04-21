"""
Focused baud × MBC compatibility sweep for the jrodrigo GB Cart Flasher rev.c.

Different from the naive sweep: resets flasher state between attempts by sending
a single END (0x0F) byte and waiting long enough for the firmware's receive
state machine to unwind before each CONFIG. Without this, a rejected attempt
leaves the firmware waiting for a handshake byte and the next CONFIG comes
back as a bare timeout.

The rev.c firmware hardcodes three baud rates (UBRRL 2/1/0 on a 6 MHz AVR,
no U2X): 125000, 187500, 375000. We also include 185000 because the FT232R's
fractional divisor rounds it to the same wire bits as 187500 and some
gbcflsh hosts historically labelled that rate "standard / 185000".

    python sweep_pc.py           # auto-pick COM port
    python sweep_pc.py COM8      # explicit port
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
DATA_LAST = 0x02
OP_RROM = 0x00

NINTENDO_LOGO = bytes([
    0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B,
    0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D,
    0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E,
    0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99,
    0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC,
    0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E,
])

MBCS = [
    ("ROMONLY", 0x04),
    ("MBC1",    0x01),
    ("MBC2",    0x02),
    ("MBC3",    0x03),
    ("MBC5",    0x05),
    ("RUMBLE",  0x06),
    ("AUTO",    0x00),
]

# First block = the three firmware-hardcoded speeds plus the 185000 alias.
# Second block = off-spec values to prove they never work on stock firmware.
BAUDS = [125000, 185000, 187500, 375000, 460800, 500000, 750000]


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
    pm1 = pages - 1
    p[6] = (pm1 >> 8) & 0xFF
    p[7] = pm1 & 0xFF
    c = crc16_ccitt(bytes(p[:PACKET_SIZE - 2]))
    p[PACKET_SIZE - 2] = (c >> 8) & 0xFF
    p[PACKET_SIZE - 1] = c & 0xFF
    return bytes(p)


def pick_port() -> str | None:
    for p in list_ports.comports():
        if p.vid == 0x0403 and p.pid == 0x6001:
            return p.device
    return None


def reset_flasher(s: serial.Serial):
    """Kick the firmware back to idle: drain pending RX, send an END byte, wait."""
    s.reset_input_buffer(); s.reset_output_buffer()
    try:
        s.write(bytes([END]))
        s.flush()
    except Exception:
        pass
    time.sleep(0.25)
    s.reset_input_buffer()


def attempt(s: serial.Serial, mbc_code: int, expect_frames: int = 512) -> tuple[bool, str, int]:
    reset_flasher(s)
    s.write(build_rrom(expect_frames // 256, mbc_code)); s.flush()

    bank0 = bytearray()
    frames = 0
    deadline = time.time() + 3.0
    while frames < expect_frames and time.time() < deadline:
        b = s.read(1)
        if not b:
            return False, f"timeout ({frames}f)", len(bank0)
        if b[0] == NAK:
            return False, "NAK", len(bank0)
        if b[0] == END:
            return False, "END", len(bank0)
        if b[0] != DATA:
            continue
        rest = s.read(PACKET_SIZE - 1)
        if len(rest) != PACKET_SIZE - 1:
            return False, "short", len(bank0)
        pkt = bytes([DATA]) + rest
        want = (pkt[-2] << 8) | pkt[-1]
        got = crc16_ccitt(pkt[:-2])
        if want != got:
            s.write(bytes([NAK]))
            return False, "CRC fail", len(bank0)
        bank0 += pkt[6:6 + FRAME_SIZE]
        frames += 1
        s.write(bytes([ACK]))
        deadline = time.time() + 3.0
        if pkt[1] == DATA_LAST:
            break
    if frames < expect_frames:
        return False, f"stopped @ {frames}f", len(bank0)
    logo_ok = bytes(bank0[0x0104:0x0104 + len(NINTENDO_LOGO)]) == NINTENDO_LOGO
    return logo_ok, "logo ✓" if logo_ok else "rx no logo", len(bank0)


def main(argv):
    port = argv[1] if len(argv) > 1 else pick_port()
    if not port:
        print("No FTDI 0403:6001 port found.")
        return 1
    print(f"Port: {port}")

    rows = []
    working = []
    for baud in BAUDS:
        print(f"\n{baud} baud:")
        try:
            s = serial.Serial(port, baudrate=baud, bytesize=8, parity="N", stopbits=1,
                              timeout=1.5, write_timeout=1.0)
        except serial.SerialException as e:
            print(f"  open fail: {e}")
            continue
        s.setDTR(False); s.setRTS(False)
        time.sleep(0.2)

        row = {"baud": baud}
        for name, code in MBCS:
            ok, msg, got = attempt(s, code)
            status = "✓ " + msg if ok else msg
            row[name] = status
            print(f"  {name:<8} -> {status}")
            if ok:
                working.append((baud, name))
            time.sleep(0.1)
        rows.append(row)
        s.close()
        # Give the FT232 + AVR a beat before the next baud.
        time.sleep(0.4)

    print("\n\n=== MATRIX ===")
    header = f"{'baud':>7} | " + " | ".join(f"{n:<10}" for n, _ in MBCS)
    print(header)
    print("-" * len(header))
    for r in rows:
        cells = []
        for name, _ in MBCS:
            v = r.get(name, "")
            cells.append(f"{v[:10]:<10}")
        print(f"{r['baud']:>7} | " + " | ".join(cells))

    print()
    if working:
        fastest_baud = max(b for b, _ in working)
        print(f"{len(working)} working combinations.")
        print(f"Fastest working baud: {fastest_baud}")
        print(f"MBCs at fastest: {sorted({m for b, m in working if b == fastest_baud})}")
        print(f"All working bauds: {sorted({b for b, _ in working})}")
    else:
        print("No working combos — cart unplugged or firmware confused.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
