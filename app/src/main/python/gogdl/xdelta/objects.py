from dataclasses import dataclass
from io import IOBase, BytesIO
from typing import Optional

@dataclass
class CodeTable:
    add_sizes = 17
    near_modes = 4
    same_modes = 3

    cpy_sizes = 15

    addcopy_add_max = 4
    addcopy_near_cpy_max = 6
    addcopy_same_cpy_max = 4

    copyadd_add_max = 1
    copyadd_near_cpy_max = 4
    copyadd_same_cpy_max = 4

    addcopy_max_sizes = [ [6,163,3],[6,175,3],[6,187,3],[6,199,3],[6,211,3],[6,223,3],
    [4,235,1],[4,239,1],[4,243,1]]
    copyadd_max_sizes = [[4,247,1],[4,248,1],[4,249,1],[4,250,1],[4,251,1],[4,252,1],
    [4,253,1],[4,254,1],[4,255,1]]

XD3_NOOP = 0
XD3_ADD = 1
XD3_RUN = 2
XD3_CPY = 3

@dataclass
class Instruction:
    type1:int = 0
    size1:int = 0
    type2:int = 0
    size2:int = 0

@dataclass
class HalfInstruction:
    type: int = 0
    size: int = 0
    addr: int = 0


@dataclass
class AddressCache:
    s_near = CodeTable.near_modes
    s_same = CodeTable.same_modes
    next_slot = 0
    near_array = [0 for _ in range(s_near)]
    same_array = [0 for _ in range(s_same * 256)]

    def update(self, addr):
        self.near_array[self.next_slot] = addr
        self.next_slot = (self.next_slot + 1) % self.s_near

        self.same_array[addr % (self.s_same*256)] = addr

@dataclass
class Context:
    source: IOBase
    target: IOBase

    data_sec: BytesIO
    inst_sec: BytesIO
    addr_sec: BytesIO

    acache: AddressCache
    dec_pos: int = 0
    cpy_len: int = 0
    cpy_off: int = 0
    dec_winoff: int = 0

    target_buffer: Optional[bytearray] = None
    
def build_code_table():
    table: list[Instruction] = []
    for _ in range(256):
        table.append(Instruction())

    cpy_modes = 2 + CodeTable.near_modes + CodeTable.same_modes
    i = 0
    
    table[i].type1 = XD3_RUN
    i+=1
    table[i].type1 = XD3_ADD
    i+=1

    size1 = 1

    for size1 in range(1, CodeTable.add_sizes + 1):
        table[i].type1 = XD3_ADD
        table[i].size1 = size1
        i+=1

    for mode in range(0, cpy_modes):
        table[i].type1 = XD3_CPY + mode
        i += 1
        for size1 in range(4, 4 + CodeTable.cpy_sizes):
            table[i].type1 = XD3_CPY + mode
            table[i].size1 = size1
            i+=1


    for mode in range(cpy_modes):
        for size1 in range(1, CodeTable.addcopy_add_max + 1):
            is_near = mode < (2 + CodeTable.near_modes)
            if is_near:
                max = CodeTable.addcopy_near_cpy_max
            else:
                max = CodeTable.addcopy_same_cpy_max
            for size2 in range(4, max + 1):
                table[i].type1 = XD3_ADD
                table[i].size1 = size1
                table[i].type2 = XD3_CPY + mode
                table[i].size2 = size2
                i+=1


    for mode in range(cpy_modes):
        is_near = mode < (2 + CodeTable.near_modes)
        if is_near:
            max = CodeTable.copyadd_near_cpy_max
        else:
            max = CodeTable.copyadd_same_cpy_max
        for size1 in range(4, max + 1):
            for size2 in range(1, CodeTable.copyadd_add_max + 1):
                table[i].type1 = XD3_CPY + mode
                table[i].size1 = size1
                table[i].type2 = XD3_ADD
                table[i].size2 = size2
                i+=1

    return table

CODE_TABLE = build_code_table()

class ChecksumMissmatch(AssertionError):
    pass
