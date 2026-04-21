"""
Protocol-level probe for the jrodrigo GB Cart Flasher rev.c on a PC.

Opens a chosen COM port at 185000 8N1, sends a STATUS packet (the same bytes
the Android app sends), prints the raw 72-byte response in hex, and decodes
the fields at their correct offsets. Lets us prove whether the flasher + cart
combo is readable independent of any GUI tool.

Usage:
    python probe_pc.py              # picks the first FTDI port it finds
    python probe_pc.py COM8         # force a specific port
    python probe_pc.py COM8 125000  # force baud as well
"""
from __future__ import annotations

import sys
import time

import serial
from serial.tools import list_ports

PACKET_SIZE = 72
DATA, ACK, NAK, END = 0x55, 0xAA, 0xF0, 0x0F
CMD_STATUS = 0x04
MBC_AUTO = 0x00
ALG_16 = 0x00


def crc16_ccitt(data: bytes) -> int:
    crc = 0
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if (crc & 0x8000) else (crc << 1) & 0xFFFF
    return crc


def build_status_packet() -> bytes:
    p = bytearray(PACKET_SIZE)
    p[0] = DATA
    p[1] = CMD_STATUS
    p[2] = 0           # id
    p[3] = MBC_AUTO
    p[4] = ALG_16
    c = crc16_ccitt(bytes(p[:PACKET_SIZE - 2]))
    p[PACKET_SIZE - 2] = (c >> 8) & 0xFF
    p[PACKET_SIZE - 1] = c & 0xFF
    return bytes(p)


def pick_port() -> str | None:
    for p in list_ports.comports():
        if p.vid == 0x0403 and p.pid == 0x6001:
            return p.device
    return None


def main():
    port = sys.argv[1] if len(sys.argv) > 1 else pick_port()
    baud = int(sys.argv[2]) if len(sys.argv) > 2 else 185000
    if not port:
        print("No FTDI 0403:6001 COM port found.")
        return 1
    print(f"Opening {port} @ {baud} 8N1 ...")

    with serial.Serial(port, baudrate=baud, bytesize=8, parity="N", stopbits=1,
                       timeout=3.0, write_timeout=2.0) as s:
        # Match the host tool: drop DTR/RTS so we don't hold the AVR in reset.
        s.setDTR(False)
        s.setRTS(False)
        s.reset_input_buffer()
        s.reset_output_buffer()
        time.sleep(0.2)

        req = build_status_packet()
        print("TX:", req.hex(" "))
        s.write(req)
        s.flush()

        # Read first byte of response.
        first = s.read(1)
        if not first:
            print("RX: <timeout — no bytes>")
            return 2
        print(f"RX[0] = 0x{first[0]:02X}")

        if first[0] == NAK:
            print("Device NAK'd the request (bad CRC or unrecognized command).")
            return 3
        if first[0] == END:
            print("Device sent END.")
            return 3
        if first[0] != DATA:
            print(f"Unexpected marker 0x{first[0]:02X}.")
            return 3

        rest = s.read(PACKET_SIZE - 1)
        if len(rest) != PACKET_SIZE - 1:
            print(f"Short read: only {1 + len(rest)} of {PACKET_SIZE} bytes.")
            return 4
        pkt = bytes([DATA]) + rest
        print("RX:", pkt.hex(" "))

        want = (pkt[-2] << 8) | pkt[-1]
        got = crc16_ccitt(pkt[:-2])
        print(f"CRC: want=0x{want:04X} got=0x{got:04X} {'OK' if want == got else 'MISMATCH'}")

        name = bytes(b for b in pkt[9:9 + 16] if 0x20 <= b < 0x7F).decode("ascii", errors="replace").rstrip()
        print("--- decoded ---")
        print(f"  packet type byte [1]  = 0x{pkt[1]:02X}")
        print(f"  status byte      [2]  = 0x{pkt[2]:02X}")
        print(f"  status byte      [3]  = 0x{pkt[3]:02X}")
        print(f"  manufacturer ID  [4]  = 0x{pkt[4]:02X}")
        print(f"  chip ID          [5]  = 0x{pkt[5]:02X}")
        print(f"  game name [9:25]      = {name!r}")
        print(f"  cart type        [28] = 0x{pkt[28]:02X}")
        print(f"  ROM size code    [29] = 0x{pkt[29]:02X}")
        print(f"  RAM size code    [30] = 0x{pkt[30]:02X}")

        # Be polite and ACK.
        s.write(bytes([ACK]))

    return 0


if __name__ == "__main__":
    sys.exit(main())
