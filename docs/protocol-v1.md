# Sharing protocol 1.0

Inside TLS, each frame is a four-byte unsigned big-endian length followed by a
one-byte message type and body. Length includes the type, excludes the prefix,
and cannot be zero. IDs: Hello 1, Offer 2, Accept 3, Reject 4, Data 5, Finish 6,
Complete 7, Failed 8, Cancel 9. Unknown types and Hello major versions other
than 1 are rejected. Minor versions are accepted for additive extensions.

Control frames have a maximum length of 65536 including type. The canonical
schema is [share_v1.proto](../proto/share_v1.proto). All listed fields must be
present, including zero byte_count/minor and empty reason/name/MIME values.
IDs are exactly 16 bytes; nonce, fingerprint, and SHA-256 are exactly 32 bytes.
Content types are Text 1, Image 2, File 3. Strings must be valid UTF-8.
Display names are metadata, never destination paths.

The C++ implementation implements our schema directly using the documented
[Protocol Buffers encoding](https://protobuf.dev/programming-guides/encoding/).
It has no protoc/runtime dependency, per the approved dependency fallback.
It emits ascending field numbers and explicit presence, including default
values. Compatible decoders may accept other field orders and nonminimal
varints; duplicate scalar/string fields use the last value. Well-formed unknown
fields are ignored (including fixed32/fixed64 and groups with nesting at most
32). Invalid field numbers, wire types, truncated lengths and overflowing
varints are rejected. Known fields with incorrect wire types are rejected.
No field numbers may be repurposed.

Data is not protobuf: 16-byte transfer ID, eight-byte unsigned big-endian
sequence, then 0 through 262144 payload bytes. Thus Data length is 25 through
262169. The parser checks prefix and type limits before buffering bodies and
retains at most one frame. Feed supports arbitrary fragmentation and multiple
frames. Any malformed frame poisons the parser; close the connection and
discard any previously decoded messages from that failed Feed call. Encode
throws std::invalid_argument for invalid input. Feed returns InvalidInput or
VersionMismatch. Callers serialize codec use and enforce connection/instance
buffer budgets and transfer state/order; the codec does not authorize content.

## Canonical test vectors

Hex below describes complete frames; `11 × 16` means exactly sixteen bytes 11.
These are concrete repeated-byte fixture notation, with no omitted bytes.

Hello: device_id 11 × 16, major 1, minor 0, nonce 22 × 32, fingerprint 33 × 32:
```
00 00 00 5b 01 0a 10 [11 × 16] 10 01 18 00 22 20 [22 × 32] 2a 20 [33 × 32]
```
Offer: transfer_id 44 × 16, File, display_name a, MIME b, byte_count 150:
```
00 00 00 1e 02 0a 10 [44 × 16] 10 03 1a 01 61 22 01 62 28 96 01
```
Data: transfer_id 55 × 16, sequence 0x0102030405060708, payload aa bb:
```
00 00 00 1b 05 [55 × 16] 01 02 03 04 05 06 07 08 aa bb
```

The release-safe protocol test constructs these byte arrays independently of
the encoder and checks byte equality. Generated-code interoperability testing
is deferred until a Protobuf toolchain is available.
