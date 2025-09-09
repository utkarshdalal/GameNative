from dataclasses import dataclass
from enum import Flag, auto
from typing import Optional


class BaseDiff:
    def __init__(self):
        self.deleted = []
        self.new = []
        self.changed = []
        self.redist = []
        self.removed_redist = []

        self.links = [] # Unix only

    def __str__(self):
        return f"Deleted: {len(self.deleted)} New: {len(self.new)} Changed: {len(self.changed)}"

class TaskFlag(Flag):
    NONE = 0
    SUPPORT = auto()
    OPEN_FILE = auto()
    CLOSE_FILE = auto()
    CREATE_FILE = auto()
    CREATE_SYMLINK = auto()
    RENAME_FILE = auto()
    COPY_FILE = auto()
    DELETE_FILE = auto()
    OFFLOAD_TO_CACHE = auto()
    MAKE_EXE = auto()
    PATCH = auto()
    RELEASE_MEM = auto()
    ZIP_DEC = auto()

@dataclass
class MemorySegment:
    offset: int
    end: int

    @property
    def size(self):
        return self.end - self.offset

@dataclass
class ChunkTask:
    product: str
    index: int

    compressed_md5: str
    md5: str

    compressed_size: int
    size: int

    memory_segments: list[MemorySegment]

    flag: TaskFlag

@dataclass
class Task:
    flag: TaskFlag
    file_path: Optional[str] = None
    file_index: Optional[int] = None

    chunks: Optional[list[ChunkTask]] = None

    target_path: Optional[str] = None
    source_path: Optional[str] = None

    old_file_index: Optional[int] = None

    data: Optional[bytes] = None

@dataclass
class FileTask:
    index: int
    path: str
    md5: str
    size: int
    chunks: list[ChunkTask]

    flag: TaskFlag

@dataclass
class FileInfo:
    index: int
    path: str
    md5: str
    size: int

    def __eq__(self, other):
        if not isinstance(other, FileInfo):
            return False
        return (self.path, self.md5, self.size) == (other.path, other.md5, other.size)

    def __ne__(self, other):
        return not self.__eq__(other)

    def __hash__(self):
        return hash((self.path, self.md5, self.size))
