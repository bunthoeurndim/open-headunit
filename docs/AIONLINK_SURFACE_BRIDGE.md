# AionLink surface bridge (experimental)

This fork of [Open Headunit](https://github.com/andreknieriem/open-headunit)
adds an optional interface for a companion app that is signed with the same
certificate. The companion supplies a video `Surface` to the bound service;
Open Headunit uses it as its decoder output and advances the existing connection
when the surface is available. It does not move an activity to a virtual
display or request Android's privileged activity-embedding permission.

The signature-level permission
`com.andrerinas.openheadunit.permission.AIONLINK_SURFACE` restricts service
binding to applications signed with the same key. Both applications must be
built and signed accordingly. Store builds and independently signed builds
cannot be assumed to interoperate. Do not publish signing keys.

While the bridge has an attached surface, this fork suppresses its normal
projection activity raise and the decoder pause on a screen-off event. Other
teardown and sleep paths remain in effect. Disconnecting the client detaches
the target surface. After the transport starts, the bridge requests video
focus and retries while no frame has been reported. It also accepts touch
events from the attached client, validates them and scales frame coordinates
to the negotiated video size. This assumes a direct scale; the car declares a
1920x972 touch frame, and touch delivery and multitouch remain under
diagnosis. The fork logs every bridge touch (log tag `OPENHU`): gate drops,
mapper rejections and accepted touches with their mapped coordinates.

End-to-end runs have verified the bridge with the GAC AION i60
(2026-11-15): decoder-to-encoder surface compatibility, Android Auto self
mode, image, locked-screen operation, audio and reconnection all work in the
car. Touch delivery is the remaining open item. Do not rely on this prototype
while driving.

Original project copyright and AGPL-3.0 license remain in effect; consult
[LICENSE](../LICENSE) and the original notices before distributing builds.
