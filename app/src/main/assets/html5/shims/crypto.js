// gamenative html5 crypto shim -- pure-JS AES-256-CTR for require("crypto").
// node compatibility surface: createDecipheriv(algorithm, key, iv) returning a Cipher with
// .update(chunk) + .final(). returns a Uint8Array; callers wrap via Buffer.concat or compare
// bytes directly. only AES-256-CTR is implemented -- other algorithms throw NOT_IMPLEMENTED_V1.

// scope: any html5 title using node crypto for symmetric decrypt of game data. originally
// motivated by OMORI's Encryption class but the API is generic node.crypto.

// AES-256: 256-bit (32-byte) key, 14 rounds, 240-byte expanded round key. CTR mode is
// stateless -- XOR plaintext with AES_ECB_encrypt(counter), increment counter, repeat.
// IV is the initial 16-byte counter; we increment as a big-endian 128-bit integer per block.

// THIS IS NOT CRYPTO-SAFE FOR ENCRYPTION. it's a decrypt path for game-bundled assets where
// the key+IV are baked in and shipped publicly. zero side-channel resistance, no constant-time
// ops; do not use for anything sensitive.
(function () {
    'use strict';

    // ----- AES-256 round-key expansion + block encrypt -----

    var sbox = new Uint8Array([
        0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
        0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
        0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
        0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
        0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
        0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
        0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
        0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
        0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
        0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
        0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
        0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
        0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
        0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
        0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
        0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
    ]);
    var rcon = new Uint8Array([0x00, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36, 0x6c, 0xd8, 0xab, 0x4d]);

    function xtime(b) { return ((b << 1) ^ ((b & 0x80) ? 0x1b : 0)) & 0xff; }

    // expand a 32-byte key into 240 bytes of round keys (15 round keys x 16 bytes).
    function expandKey256(key) {
        var rk = new Uint8Array(240);
        for (var i = 0; i < 32; i++) rk[i] = key[i];
        var bytesGenerated = 32;
        var rconIter = 1;
        var temp = new Uint8Array(4);
        while (bytesGenerated < 240) {
            for (var t = 0; t < 4; t++) temp[t] = rk[bytesGenerated - 4 + t];
            if (bytesGenerated % 32 === 0) {
                // RotWord
                var tt = temp[0]; temp[0] = temp[1]; temp[1] = temp[2]; temp[2] = temp[3]; temp[3] = tt;
                // SubWord
                temp[0] = sbox[temp[0]]; temp[1] = sbox[temp[1]]; temp[2] = sbox[temp[2]]; temp[3] = sbox[temp[3]];
                temp[0] ^= rcon[rconIter];
                rconIter++;
            } else if (bytesGenerated % 32 === 16) {
                temp[0] = sbox[temp[0]]; temp[1] = sbox[temp[1]]; temp[2] = sbox[temp[2]]; temp[3] = sbox[temp[3]];
            }
            for (var u = 0; u < 4; u++) {
                rk[bytesGenerated] = rk[bytesGenerated - 32] ^ temp[u];
                bytesGenerated++;
            }
        }
        return rk;
    }

    function aes256EncryptBlock(state, rk) {
        // AddRoundKey round 0
        for (var i = 0; i < 16; i++) state[i] ^= rk[i];
        for (var round = 1; round < 14; round++) {
            // SubBytes
            for (var s = 0; s < 16; s++) state[s] = sbox[state[s]];
            // ShiftRows
            var t = state[1]; state[1] = state[5]; state[5] = state[9]; state[9] = state[13]; state[13] = t;
            t = state[2]; state[2] = state[10]; state[10] = t; t = state[6]; state[6] = state[14]; state[14] = t;
            t = state[15]; state[15] = state[11]; state[11] = state[7]; state[7] = state[3]; state[3] = t;
            // MixColumns
            for (var c = 0; c < 4; c++) {
                var a0 = state[c * 4], a1 = state[c * 4 + 1], a2 = state[c * 4 + 2], a3 = state[c * 4 + 3];
                var x = a0 ^ a1 ^ a2 ^ a3;
                state[c * 4] ^= x ^ xtime(a0 ^ a1);
                state[c * 4 + 1] ^= x ^ xtime(a1 ^ a2);
                state[c * 4 + 2] ^= x ^ xtime(a2 ^ a3);
                state[c * 4 + 3] ^= x ^ xtime(a3 ^ a0);
            }
            // AddRoundKey
            var off = round * 16;
            for (var k = 0; k < 16; k++) state[k] ^= rk[off + k];
        }
        // final round (no MixColumns)
        for (var s2 = 0; s2 < 16; s2++) state[s2] = sbox[state[s2]];
        var t2 = state[1]; state[1] = state[5]; state[5] = state[9]; state[9] = state[13]; state[13] = t2;
        t2 = state[2]; state[2] = state[10]; state[10] = t2; t2 = state[6]; state[6] = state[14]; state[14] = t2;
        t2 = state[15]; state[15] = state[11]; state[11] = state[7]; state[7] = state[3]; state[3] = t2;
        for (var k2 = 0; k2 < 16; k2++) state[k2] ^= rk[224 + k2];
    }

    // increment a 16-byte big-endian counter in place.
    function incCounter(c) {
        for (var i = 15; i >= 0; i--) {
            c[i] = (c[i] + 1) & 0xff;
            if (c[i] !== 0) break;
        }
    }

    // ----- Cipher object (createDecipheriv) -----

    function unwrapBytes(input) {
        if (input == null) return new Uint8Array(0);
        if (input instanceof Uint8Array) return input;
        if (input instanceof ArrayBuffer) return new Uint8Array(input);
        if (input && typeof input === 'object' && input.__isGnBuffer === true && input._bytes) return input._bytes;
        if (typeof input === 'string') {
            // node default for createDecipheriv key/iv strings: utf-8.
            var enc = unescape(encodeURIComponent(input));
            var out = new Uint8Array(enc.length);
            for (var i = 0; i < enc.length; i++) out[i] = enc.charCodeAt(i);
            return out;
        }
        if (input.length !== undefined) {
            var n = new Uint8Array(input.length);
            for (var j = 0; j < input.length; j++) n[j] = input[j] & 0xff;
            return n;
        }
        throw new Error('crypto: unsupported input type ' + typeof input);
    }

    function wrapBytesMaybe(bytes) {
        // mirrors fs.js's wrapBytes when Buffer global is available so callers can chain
        // .toString('utf8'). when missing, return Uint8Array -- most node code tolerates either.
        if (typeof window.Buffer !== 'undefined' && typeof window.Buffer.from === 'function') {
            return window.Buffer.from(bytes);
        }
        return bytes;
    }

    function createAesCtrDecipher(key, iv) {
        if (key.length !== 32) throw new Error('AES-256-CTR key must be 32 bytes (got ' + key.length + ')');
        if (iv.length !== 16) throw new Error('AES-256-CTR iv must be 16 bytes (got ' + iv.length + ')');
        var rk = expandKey256(key);
        var counter = new Uint8Array(iv);
        var keystream = new Uint8Array(16);
        var keystreamPos = 16; // empty initially

        function fillKeystream() {
            // copy counter into keystream then encrypt in-place
            for (var i = 0; i < 16; i++) keystream[i] = counter[i];
            aes256EncryptBlock(keystream, rk);
            incCounter(counter);
            keystreamPos = 0;
        }

        return {
            update: function (data) {
                var bytes = unwrapBytes(data);
                if (bytes.length === 0) return wrapBytesMaybe(new Uint8Array(0));
                var out = new Uint8Array(bytes.length);
                for (var i = 0; i < bytes.length; i++) {
                    if (keystreamPos === 16) fillKeystream();
                    out[i] = bytes[i] ^ keystream[keystreamPos++];
                }
                return wrapBytesMaybe(out);
            },
            final: function () {
                // CTR has no padding so final is empty. tracked separately so caller's
                // Buffer.concat([decipher.update(...), decipher.final()]) returns full plaintext.
                return wrapBytesMaybe(new Uint8Array(0));
            },
        };
    }

    // ----- module surface -----

    var cryptoModule = {
        createDecipheriv: function (algorithm, key, iv) {
            var algo = String(algorithm).toLowerCase();
            if (algo !== 'aes-256-ctr') {
                throw new Error('NOT_IMPLEMENTED_V1: crypto.createDecipheriv("' + algorithm + '")');
            }
            return createAesCtrDecipher(unwrapBytes(key), unwrapBytes(iv));
        },
        // proxy for arbitrary other crypto.* -- log + throw so device-smoke catalogues missing surface.
        // Object.assigned-into when needed. native node has dozens of methods; we ship the one we need.
    };

    if (window.require && typeof window.require.register === 'function') {
        window.require.register('crypto', cryptoModule);
        if (self.__gnShimVerbose) try { console.log('gamenative crypto shim registered (AES-256-CTR)'); } catch (e) {}
    }
})();
