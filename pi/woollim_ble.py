"""
울림 감지 장치 BLE GATT 서버 (Raspberry Pi = Peripheral, 앱 = Central)

서비스 1개, characteristic 2개 — 앱 android/.../source/BleEventSource.kt 와 UUID가 같아야 한다.
  event : Notify  HazardEvent JSON 한 줄 + '\n' (UTF-8), 20 byte 조각으로 나눠 전송
  level : Notify  마이크 입력 레벨 1 byte (0..255)  홈 화면 "마이크 입력" 막대용

JSON(약 110 byte)이 기본 패킷 크기(20 byte)보다 크므로 앱이 연결 직후 MTU를 247로 올린다.
"""
from __future__ import annotations

import asyncio
import json
import logging

from bless import (
    BlessGATTCharacteristic,
    BlessServer,
    GATTAttributePermissions,
    GATTCharacteristicProperties,
)

SERVICE_UUID = "8f1e0001-6b2c-4d3a-9e5f-77a1c0d2e301"
EVENT_UUID = "8f1e0002-6b2c-4d3a-9e5f-77a1c0d2e301"
LEVEL_UUID = "8f1e0003-6b2c-4d3a-9e5f-77a1c0d2e301"

DEVICE_NAME = "Woollim"
CHUNK = 20  # 기본 ATT MTU(23) - 헤더(3). MTU가 커도 이 크기면 항상 안전

log = logging.getLogger("woollim.ble")


class WoollimBle:
    def __init__(self, name: str = DEVICE_NAME):
        self.name = name
        self.server: BlessServer | None = None

    async def start(self) -> None:
        loop = asyncio.get_running_loop()
        self.server = BlessServer(name=self.name, loop=loop)
        self.server.read_request_func = self._on_read

        props = GATTCharacteristicProperties.read | GATTCharacteristicProperties.notify
        perms = GATTAttributePermissions.readable
        await self.server.add_new_service(SERVICE_UUID)
        await self.server.add_new_characteristic(SERVICE_UUID, EVENT_UUID, props, bytearray(b"{}"), perms)
        await self.server.add_new_characteristic(SERVICE_UUID, LEVEL_UUID, props, bytearray(b"\x00"), perms)
        await self.server.start()
        log.info("BLE 광고 시작: %s (service %s)", self.name, SERVICE_UUID)

    async def stop(self) -> None:
        if self.server is not None:
            await self.server.stop()

    @staticmethod
    def _on_read(characteristic: BlessGATTCharacteristic, **kwargs) -> bytearray:
        return characteristic.value

    def _notify(self, char_uuid: str, data: bytes) -> None:
        if self.server is None:
            return
        ch = self.server.get_characteristic(char_uuid)
        ch.value = bytearray(data)
        # 구독한 앱이 없으면 값만 바뀌고 아무것도 전송되지 않는다
        self.server.update_value(SERVICE_UUID, char_uuid)

    def send_event(self, event: dict) -> None:
        """
        inference_reference.emit() 자리: 이벤트 JSON 한 줄을 Notify.
        MTU 협상이 실패해도 잘리지 않도록 20 byte 조각으로 나눠 보내고 끝에 '\\n'을 붙인다.
        앱(BleEventSource)은 '\\n'이 올 때까지 조각을 이어 붙여 한 줄로 해석한다.
        """
        line = json.dumps(event, ensure_ascii=False, separators=(",", ":"))
        data = (line + "\n").encode("utf-8")
        for i in range(0, len(data), CHUNK):
            self._notify(EVENT_UUID, data[i:i + CHUNK])
        print(line, flush=True)

    def send_level(self, level01: float) -> None:
        """0..1 입력 레벨 → 1 byte"""
        v = int(round(min(max(level01, 0.0), 1.0) * 255))
        self._notify(LEVEL_UUID, bytes([v]))
