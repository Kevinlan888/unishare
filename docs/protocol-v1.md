# Sharing protocol 1.0

Inside TLS, each frame is a four-byte unsigned big-endian length followed by a
one-byte message type and body. Length includes the type, excludes the prefix,
and cannot be zero. IDs: Hello 1, Offer 2, Accept 3, Reject 4, Data 5, Finish 6,
Complete 7, Failed 8, Cancel 9, PairDecision 10. Unknown types and Hello major versions other
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

## TLS and connection authorization

Each endpoint requires a self-signed P-256 identity certificate and proof of its
private key through mutual TLS 1.3. TLS 1.2, resumption and early data are disabled.
A self-signed chain alone confers no trust. The Hello fingerprint must equal
SHA-256 of the actual TLS peer certificate's DER SubjectPublicKeyInfo. An outgoing
connection also verifies the Hello DeviceId equals the selected candidate ID.
An existing DeviceId pin mismatch emits IdentityChanged and never overwrites it.

After exactly one Hello each way, PairDecision (type 10) has mandatory protobuf
field 1 `confirmed` (wire type varint, strictly 0 or 1). Its complete canonical
frames are `00 00 00 03 0a 08 01` for acceptance and `00 00 00 03 0a 08 00` for
rejection. Missing/invalid values, a decision before Hello, and repeated decisions
are rejected. This was added before any v1 release; version remains 1.0.

The SAS is SHA-256 over the exact 192-byte concatenation:

```
exporter[32] || initiator DeviceId[16] || initiator SPKI hash[32] || initiator nonce[32]
             || responder DeviceId[16] || responder SPKI hash[32] || responder nonce[32]
```

Exporter label: `EXPORTER-unishare-pairing-v1`, empty context, output 32 bytes.
Interpret the first four digest bytes as unsigned big-endian, modulo 1000000,
then zero-pad to six digits. IDs use binary UUID bytes, not printable strings.
Fixture: exporter 07 x32; initiator ID 01 then 15 zeroes, hash 05 x32, nonce 03 x32;
responder ID 02 then 15 zeroes, hash 06 x32, nonce 04 x32 gives SAS `317527`.

Unknown peers require explicit affirmative decisions from both users. A pinned
initiator's explicit Connect is its consent; a pinned receiver still receives a
PairingRequested event for this connection and must confirm. Both endpoints send
PairDecision. Trust is pinned only after local consent, receipt of peer consent,
and completion of the local decision's encrypted socket write. A rejection is
sent before closing if the transport remains writable. Approval/handshake has a
60-second monotonic deadline. No content is authorized before Connected.

Durable insertion is atomic at each device, not a distributed transaction. If
one store fails or the network drops after the other's successful insertion,
the successful local pin remains. Reconnect checks each local pin independently;
forgetting an existing pin is always explicit. No network error replaces a pin.

SessionAdmission is shared by the SDK's sessions: at most eight live transports,
four pending confirmations, and three requests per canonical transport source
address in a rolling 60-second window. The source excludes ephemeral port and
normalizes IPv4-mapped IPv6; link-local IPv6 includes its scope. Adapters supply
this metadata via ISocket::RemoteEndpoint; Hello identity is never the rate key.
Source history is capped at 1024 active source entries and fails closed when full.

The portable core uses OpenSSL memory BIOs, one outstanding socket read/write,
and a serialized monotonic ISessionScheduler. Socket completions may occur inline
or on any thread; the core posts them onto that scheduler. Scheduler, admission,
trust store, factory and callback executor must outlive their sessions and queued
transport completions. All Session API calls must run on the same serial core
queue. Session objects must be shared_ptr-owned. Close is terminal and idempotent;
late callbacks are ignored. Event handlers run on the configured executor.

This task implements connection negotiation. Transfer message dispatch is added
by the transfer state machine; the current negotiation component rejects content
messages rather than delivering any unapproved payload.
