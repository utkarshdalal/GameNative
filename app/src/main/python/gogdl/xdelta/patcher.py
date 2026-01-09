from io import BytesIO
import math
from multiprocessing import Queue
from zlib import adler32
from gogdl.xdelta import objects

# Convert stfio integer
def read_integer_stream(stream):
    res = 0
    while True:
        res <<= 7
        integer = stream.read(1)[0]
        res |= (integer & 0b1111111)
        if not (integer & 0b10000000):
            break

    return res

def parse_halfinst(context: objects.Context, halfinst: objects.HalfInstruction):
    if halfinst.size == 0:
        halfinst.size = read_integer_stream(context.inst_sec)

    if halfinst.type >= objects.XD3_CPY:
        # Decode address
        mode = halfinst.type - objects.XD3_CPY
        same_start = 2 + context.acache.s_near

        if mode < same_start:
            halfinst.addr = read_integer_stream(context.addr_sec)

            if mode == 0:
                pass
            elif mode == 1:
                halfinst.addr = context.dec_pos - halfinst.addr
                if halfinst.addr < 0:
                    halfinst.addr = context.cpy_len + halfinst.addr
            else:
                halfinst.addr += context.acache.near_array[mode - 2]
        else:
            mode -= same_start
            addr = context.addr_sec.read(1)[0]
            halfinst.addr = context.acache.same_array[(mode * 256) + addr]
        context.acache.update(halfinst.addr)

    context.dec_pos += halfinst.size


def decode_halfinst(context:objects.Context, halfinst: objects.HalfInstruction, speed_queue: Queue):
    take = halfinst.size

    if halfinst.type == objects.XD3_RUN:
        byte = context.data_sec.read(1)

        for _ in range(take):
            context.target_buffer.extend(byte)

        halfinst.type = objects.XD3_NOOP
    elif halfinst.type == objects.XD3_ADD:
        buffer = context.data_sec.read(take)
        assert len(buffer) == take
        context.target_buffer.extend(buffer)
        halfinst.type = objects.XD3_NOOP
    else: # XD3_CPY and higher
        if halfinst.addr < (context.cpy_len or 0):
            context.source.seek(context.cpy_off + halfinst.addr)
            left = take
            while left > 0:
                buffer = context.source.read(min(1024 * 1024, left))
                size = len(buffer)
                speed_queue.put((0, size))
                context.target_buffer.extend(buffer)
                left -= size

        else:
            print("OVERLAP NOT IMPLEMENTED")
            raise Exception("OVERLAP")
        halfinst.type = objects.XD3_NOOP


def patch(source: str, patch: str, out: str, speed_queue: Queue):
    src_handle = open(source, 'rb') 
    patch_handle = open(patch, 'rb')
    dst_handle = open(out, 'wb')


    # Verify if patch is actually xdelta patch
    headers = patch_handle.read(5)
    try:
        assert headers[0] == 0xD6
        assert headers[1] == 0xC3
        assert headers[2] == 0xC4
    except AssertionError:
        print("Specified patch file is unlikely to be xdelta patch")
        return

    HDR_INDICATOR = headers[4]
    COMPRESSOR_ID = HDR_INDICATOR & (1 << 0) != 0
    CODE_TABLE = HDR_INDICATOR & (1 << 1) != 0
    APP_HEADER = HDR_INDICATOR & (1 << 2) != 0
    app_header_data = bytes()

    if COMPRESSOR_ID or CODE_TABLE:
        print("Compressor ID and codetable are yet not supported")
        return

    if APP_HEADER:
        app_header_size = read_integer_stream(patch_handle)
        app_header_data = patch_handle.read(app_header_size)

    context = objects.Context(src_handle, dst_handle, BytesIO(), BytesIO(), BytesIO(), objects.AddressCache())

    win_number = 0
    win_indicator = patch_handle.read(1)[0]
    while win_indicator is not None:
        context.acache = objects.AddressCache()
        source_used = win_indicator & (1 << 0) != 0
        target_used = win_indicator & (1 << 1) != 0
        adler32_sum = win_indicator & (1 << 2) != 0

        if source_used:
            source_segment_length = read_integer_stream(patch_handle)
            source_segment_position = read_integer_stream(patch_handle)
        else:
            source_segment_length = 0
            source_segment_position = 0

        context.cpy_len = source_segment_length
        context.cpy_off = source_segment_position
        context.source.seek(context.cpy_off or 0)
        context.dec_pos = 0

        # Parse delta
        delta_encoding_length = read_integer_stream(patch_handle)

        window_length = read_integer_stream(patch_handle)
        context.target_buffer = bytearray()

        delta_indicator = patch_handle.read(1)[0]
        
        add_run_data_length = read_integer_stream(patch_handle)
        instructions_length = read_integer_stream(patch_handle)
        addresses_length = read_integer_stream(patch_handle)

        parsed_sum = 0
        if adler32_sum:
            checksum = patch_handle.read(4)
            parsed_sum = int.from_bytes(checksum, 'big')
        

        context.data_sec = BytesIO(patch_handle.read(add_run_data_length))
        context.inst_sec = BytesIO(patch_handle.read(instructions_length))
        context.addr_sec = BytesIO(patch_handle.read(addresses_length))


        current1 = objects.HalfInstruction()
        current2 = objects.HalfInstruction()

        while context.inst_sec.tell() < instructions_length or current1.type != objects.XD3_NOOP or current2.type != objects.XD3_NOOP:
            if current1.type == objects.XD3_NOOP and current2.type == objects.XD3_NOOP:
                ins = objects.CODE_TABLE[context.inst_sec.read(1)[0]]
                current1.type = ins.type1
                current2.type = ins.type2
                current1.size = ins.size1
                current2.size = ins.size2
    
                if current1.type != objects.XD3_NOOP:
                    parse_halfinst(context, current1)
                if current2.type != objects.XD3_NOOP:
                    parse_halfinst(context, current2)
            
            while current1.type != objects.XD3_NOOP:
                decode_halfinst(context, current1, speed_queue)
                
            while current2.type != objects.XD3_NOOP:
                decode_halfinst(context, current2, speed_queue)

        if adler32_sum:
            calculated_sum = adler32(context.target_buffer)
            if parsed_sum != calculated_sum:
                raise objects.ChecksumMissmatch

        total_size = len(context.target_buffer)
        chunk_size = 1024 * 1024
        for i in range(math.ceil(total_size / chunk_size)):
            chunk = context.target_buffer[i * chunk_size : min((i + 1) * chunk_size, total_size)]
            context.target.write(chunk)
            speed_queue.put((len(chunk), 0))
            
        context.target.flush()

        indicator = patch_handle.read(1)
        if not len(indicator):
            win_indicator = None
            continue
        win_indicator = indicator[0]
        win_number += 1


    dst_handle.flush()
    src_handle.close()
    patch_handle.close()
    dst_handle.close()

 
