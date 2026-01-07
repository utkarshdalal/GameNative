from io import BytesIO
import stat


END_OF_CENTRAL_DIRECTORY = b"\x50\x4b\x05\x06"
CENTRAL_DIRECTORY = b"\x50\x4b\x01\x02"
LOCAL_FILE_HEADER = b"\x50\x4b\x03\x04"

# ZIP64
ZIP_64_END_OF_CD_LOCATOR = b"\x50\x4b\x06\x07"
ZIP_64_END_OF_CD = b"\x50\x4b\x06\x06"

class LocalFile:
    def __init__(self) -> None:
        self.relative_local_file_offset: int
        self.version_needed: bytes
        self.general_purpose_bit_flag: bytes
        self.compression_method: int
        self.last_modification_time: bytes
        self.last_modification_date: bytes
        self.crc32: bytes
        self.compressed_size: int
        self.uncompressed_size: int
        self.file_name_length: int
        self.extra_field_length: int
        self.file_name: str
        self.extra_field: bytes
        self.last_byte: int

    def load_data(self, handler):
        return handler.get_bytes_from_file(
            from_b=self.last_byte + self.relative_local_file_offset,
            size=self.compressed_size,
            raw_response=True
        )

    @classmethod
    def from_bytes(cls, data, offset, handler):
        local_file = cls()
        local_file.relative_local_file_offset = 0
        local_file.version_needed = data[4:6]
        local_file.general_purpose_bit_flag = data[6:8]
        local_file.compression_method = int.from_bytes(data[8:10], "little")
        local_file.last_modification_time = data[10:12]
        local_file.last_modification_date = data[12:14]
        local_file.crc32 = data[14:18]
        local_file.compressed_size = int.from_bytes(data[18:22], "little")
        local_file.uncompressed_size = int.from_bytes(data[22:26], "little")
        local_file.file_name_length = int.from_bytes(data[26:28], "little")
        local_file.extra_field_length = int.from_bytes(data[28:30], "little")

        extra_data = handler.get_bytes_from_file(
            from_b=30 + offset,
            size=local_file.file_name_length + local_file.extra_field_length,
        )

        local_file.file_name = bytes(
            extra_data[0: local_file.file_name_length]
        ).decode()

        local_file.extra_field = data[
                                 local_file.file_name_length: local_file.file_name_length
                                                              + local_file.extra_field_length
                                 ]
        local_file.last_byte = (
                local_file.file_name_length + local_file.extra_field_length + 30
        )
        return local_file

    def __str__(self):
        return f"\nCompressionMethod: {self.compression_method} \nFileNameLen: {self.file_name_length} \nFileName: {self.file_name} \nCompressedSize: {self.compressed_size} \nUncompressedSize: {self.uncompressed_size}"


class CentralDirectoryFile:
    def __init__(self, product):
        self.product = product
        self.version_made_by: bytes
        self.version_needed_to_extract: bytes
        self.general_purpose_bit_flag: bytes
        self.compression_method: int
        self.last_modification_time: bytes
        self.last_modification_date: bytes
        self.crc32: int
        self.compressed_size: int
        self.uncompressed_size: int
        self.file_name_length: int
        self.extra_field_length: int
        self.file_comment_length: int
        self.disk_number_start: bytes
        self.int_file_attrs: bytes
        self.ext_file_attrs: bytes
        self.relative_local_file_offset: int
        self.file_name: str
        self.extra_field: BytesIO
        self.comment: bytes
        self.last_byte: int
        self.file_data_offset: int

    @classmethod
    def from_bytes(cls, data, product):
        cd_file = cls(product)

        cd_file.version_made_by = data[4:6]
        cd_file.version_needed_to_extract = data[6:8]
        cd_file.general_purpose_bit_flag = data[8:10]
        cd_file.compression_method = int.from_bytes(data[10:12], "little")
        cd_file.last_modification_time = data[12:14]
        cd_file.last_modification_date = data[14:16]
        cd_file.crc32 = int.from_bytes(data[16:20], "little")
        cd_file.compressed_size = int.from_bytes(data[20:24], "little")
        cd_file.uncompressed_size = int.from_bytes(data[24:28], "little")
        cd_file.file_name_length = int.from_bytes(data[28:30], "little")
        cd_file.extra_field_length = int.from_bytes(data[30:32], "little")
        cd_file.file_comment_length = int.from_bytes(data[32:34], "little")
        cd_file.disk_number_start = data[34:36]
        cd_file.int_file_attrs = data[36:38]
        cd_file.ext_file_attrs = data[38:42]
        cd_file.relative_local_file_offset = int.from_bytes(data[42:46], "little")
        cd_file.file_data_offset = 0

        extra_field_start = 46 + cd_file.file_name_length
        cd_file.file_name = bytes(data[46:extra_field_start]).decode()

        cd_file.extra_field = BytesIO(data[
                              extra_field_start: extra_field_start + cd_file.extra_field_length
                              ])

        field = None
        while True:
            id = int.from_bytes(cd_file.extra_field.read(2), "little")
            size = int.from_bytes(cd_file.extra_field.read(2), "little")

            if id == 0x01:
                if cd_file.extra_field_length - cd_file.extra_field.tell() >= size:
                    field = BytesIO(cd_file.extra_field.read(size))
                break

            cd_file.extra_field.seek(size, 1)

            if cd_file.extra_field_length - cd_file.extra_field.tell() == 0:
                break


        if field:
            if cd_file.uncompressed_size == 0xFFFFFFFF:
                cd_file.uncompressed_size = int.from_bytes(field.read(8), "little")

            if cd_file.compressed_size == 0xFFFFFFFF:
                cd_file.compressed_size = int.from_bytes(field.read(8), "little")

            if cd_file.relative_local_file_offset == 0xFFFFFFFF:
                cd_file.relative_local_file_offset = int.from_bytes(field.read(8), "little")

        comment_start = extra_field_start + cd_file.extra_field_length
        cd_file.comment = data[
                          comment_start: comment_start + cd_file.file_comment_length
                          ]

        cd_file.last_byte = comment_start + cd_file.file_comment_length

        return cd_file, comment_start + cd_file.file_comment_length

    def is_symlink(self):
        return stat.S_ISLNK(int.from_bytes(self.ext_file_attrs, "little") >> 16)

    def as_dict(self):
        return {'file_name': self.file_name, 'crc32': self.crc32, 'compressed_size': self.compressed_size, 'size': self.uncompressed_size, 'is_symlink': self.is_symlink()}

    def __str__(self):
        return f"\nCompressionMethod: {self.compression_method} \nFileNameLen: {self.file_name_length} \nFileName: {self.file_name} \nStartDisk: {self.disk_number_start} \nCompressedSize: {self.compressed_size} \nUncompressedSize: {self.uncompressed_size}"

    def __repr__(self):
        return self.file_name


class CentralDirectory:
    def __init__(self, product):
        self.files = []
        self.product = product

    @staticmethod
    def create_central_dir_file(data, product):
        return CentralDirectoryFile.from_bytes(data, product)

    @classmethod
    def from_bytes(cls, data, n, product):
        central_dir = cls(product)
        for record in range(n):
            cd_file, next_offset = central_dir.create_central_dir_file(data, product)
            central_dir.files.append(cd_file)
            data = data[next_offset:]
            if record == 0:
                continue

            prev_i = record - 1
            if not (prev_i >= 0 and prev_i < len(central_dir.files)):
                continue
            prev = central_dir.files[prev_i]
            prev.file_data_offset = cd_file.relative_local_file_offset - prev.compressed_size

        return central_dir

class Zip64EndOfCentralDirLocator:
    def __init__(self):
        self.number_of_disk: int
        self.zip64_end_of_cd_offset: int
        self.total_number_of_disks: int

    @classmethod
    def from_bytes(cls, data):
        zip64_end_of_cd = cls()
        zip64_end_of_cd.number_of_disk = int.from_bytes(data[4:8], "little")
        zip64_end_of_cd.zip64_end_of_cd_offset = int.from_bytes(data[8:16], "little")
        zip64_end_of_cd.total_number_of_disks = int.from_bytes(data[16:20], "little")
        return zip64_end_of_cd

    def __str__(self):
        return f"\nZIP64EOCDLocator\nDisk Number: {self.number_of_disk}\nZ64_EOCD Offset: {self.zip64_end_of_cd_offset}\nNumber of disks: {self.total_number_of_disks}"

class Zip64EndOfCentralDir:
    def __init__(self):
        self.size: int
        self.version_made_by: bytes
        self.version_needed: bytes
        self.number_of_disk: bytes
        self.central_directory_start_disk: bytes
        self.number_of_entries_on_this_disk: int
        self.number_of_entries_total: int
        self.size_of_central_directory: int
        self.central_directory_offset: int
        self.extensible_data = None

    @classmethod
    def from_bytes(cls, data):
        end_of_cd = cls()

        end_of_cd.size = int.from_bytes(data[4:12], "little")
        end_of_cd.version_made_by = data[12:14]
        end_of_cd.version_needed = data[14:16]
        end_of_cd.number_of_disk = data[16:20]
        end_of_cd.central_directory_start_disk = data[20:24]
        end_of_cd.number_of_entries_on_this_disk = int.from_bytes(data[24:32], "little")
        end_of_cd.number_of_entries_total = int.from_bytes(data[32:40], "little")
        end_of_cd.size_of_central_directory = int.from_bytes(data[40:48], "little")
        end_of_cd.central_directory_offset = int.from_bytes(data[48:56], "little")

        return end_of_cd

    def __str__(self) -> str:
        return f"\nZ64 EndOfCD\nSize: {self.size}\nNumber of disk: {self.number_of_disk}\nEntries on this disk: {self.number_of_entries_on_this_disk}\nEntries total: {self.number_of_entries_total}\nCD offset: {self.central_directory_offset}"


class EndOfCentralDir:
    def __init__(self):
        self.number_of_disk: bytes
        self.central_directory_disk: bytes
        self.central_directory_records: int
        self.size_of_central_directory: int
        self.central_directory_offset: int
        self.comment_length: bytes
        self.comment: bytes

    @classmethod
    def from_bytes(cls, data):
        central_dir = cls()
        central_dir.number_of_disk = data[4:6]
        central_dir.central_directory_disk = data[6:8]
        central_dir.central_directory_records = int.from_bytes(data[8:10], "little")
        central_dir.size_of_central_directory = int.from_bytes(data[12:16], "little")
        central_dir.central_directory_offset = int.from_bytes(data[16:20], "little")
        central_dir.comment_length = data[20:22]
        central_dir.comment = data[
                              22: 22 + int.from_bytes(central_dir.comment_length, "little")
                              ]

        return central_dir

    def __str__(self):
        return f"\nDiskNumber: {self.number_of_disk} \nCentralDirRecords: {self.central_directory_records} \nCentralDirSize: {self.size_of_central_directory} \nCentralDirOffset: {self.central_directory_offset}"


class InstallerHandler:
    def __init__(self, url, product_id, session):
        self.url = url
        self.product = product_id
        self.session = session
        self.file_size = 0

        SEARCH_OFFSET = 0
        SEARCH_RANGE = 2 * 1024 * 1024 # 2 MiB

        beginning_of_file = self.get_bytes_from_file(
            from_b=SEARCH_OFFSET, size=SEARCH_RANGE, add_archive_index=False
        )

        self.start_of_archive_index = beginning_of_file.find(LOCAL_FILE_HEADER) + SEARCH_OFFSET

        # ZIP contents
        self.central_directory_offset: int
        self.central_directory_records: int
        self.size_of_central_directory: int
        self.central_directory: CentralDirectory

    def get_bytes_from_file(self, from_b=-1, size=None, add_archive_index=True, raw_response=False, redirect_count=0):
        """Get bytes from file with redirect handling and bounds checking

        Args:
            from_b: Starting byte offset
            size: Number of bytes to read
            add_archive_index: Whether to add archive index offset
            raw_response: Whether to return raw response object
            redirect_count: Current redirect depth (internal, max 5)

        Returns:
            Response object or bytes data
        """
        MAX_REDIRECTS = 5

        if redirect_count >= MAX_REDIRECTS:
            raise Exception(f"Too many redirects ({MAX_REDIRECTS}) when fetching bytes from {self.url}")

        # Store original from_b before applying archive index
        original_from_b = from_b
        if add_archive_index:
            from_b += self.start_of_archive_index

        from_b_repr = str(from_b) if from_b > -1 else ""
        if size:
            end_b = from_b + size - 1
        else:
            end_b = ""
        range_header = self.get_range_header(from_b_repr, end_b)

        response = self.session.get(self.url, headers={'Range': range_header},
                                    allow_redirects=False, stream=raw_response)
        if response.status_code == 302:
            # Skip content-system API and follow redirect
            self.url = response.headers.get('Location') or self.url
            # Use original from_b and set add_archive_index=False to prevent double-applying the index
            return self.get_bytes_from_file(original_from_b, size, add_archive_index=False,
                                          raw_response=raw_response, redirect_count=redirect_count + 1)

        # Safely extract file_size from Content-Range header if present
        if not self.file_size:
            content_range = response.headers.get("Content-Range")
            if content_range:
                try:
                    self.file_size = int(content_range.split("/")[-1])
                except (ValueError, IndexError) as e:
                    raise Exception(f"Invalid Content-Range header: {content_range}") from e
            else:
                raise Exception("Content-Range header missing and file_size not set")

        if raw_response:
            return response
        else:
            data = response.content
            return data

    @staticmethod
    def get_range_header(from_b="", to_b=""):
        return f"bytes={from_b}-{to_b}"

    def setup(self):
        self.__find_end_of_cd()
        self.__find_central_directory()

    def __find_end_of_cd(self):
        end_of_cd_data = self.get_bytes_from_file(
            from_b=self.file_size - 100, add_archive_index=False
        )

        end_of_cd_header_data_index = end_of_cd_data.find(END_OF_CENTRAL_DIRECTORY)
        zip64_end_of_cd_locator_index = end_of_cd_data.find(ZIP_64_END_OF_CD_LOCATOR)
        assert end_of_cd_header_data_index != -1
        end_of_cd = EndOfCentralDir.from_bytes(end_of_cd_data[end_of_cd_header_data_index:])
        if end_of_cd.central_directory_offset == 0xFFFFFFFF:
            assert zip64_end_of_cd_locator_index != -1
            # We need to find zip64 headers

            zip64_end_of_cd_locator = Zip64EndOfCentralDirLocator.from_bytes(end_of_cd_data[zip64_end_of_cd_locator_index:])
            zip64_end_of_cd_data = self.get_bytes_from_file(from_b=zip64_end_of_cd_locator.zip64_end_of_cd_offset, size=200)
            zip64_end_of_cd = Zip64EndOfCentralDir.from_bytes(zip64_end_of_cd_data)

            self.central_directory_offset = zip64_end_of_cd.central_directory_offset
            self.size_of_central_directory = zip64_end_of_cd.size_of_central_directory
            self.central_directory_records = zip64_end_of_cd.number_of_entries_total
        else:
            self.central_directory_offset = end_of_cd.central_directory_offset
            self.size_of_central_directory = end_of_cd.size_of_central_directory
            self.central_directory_records = end_of_cd.central_directory_records

    def __find_central_directory(self):
        central_directory_data = self.get_bytes_from_file(
            from_b=self.central_directory_offset,
            size=self.size_of_central_directory,
        )

        assert central_directory_data[:4] == CENTRAL_DIRECTORY

        self.central_directory = CentralDirectory.from_bytes(
            central_directory_data, self.central_directory_records, self.product
        )
        last_entry = self.central_directory.files[-1]
        last_entry.file_data_offset = self.central_directory_offset - last_entry.compressed_size


class LinuxFile:
    def __init__(self, product, path, compression, start, compressed_size, size, checksum, executable):
        self.product = product
        self.path = path
        self.compression = compression == 8
        self.offset = start
        self.compressed_size = compressed_size
        self.size = size
        self.hash = str(checksum)
        self.flags = []
        if executable:
            self.flags.append("executable")
