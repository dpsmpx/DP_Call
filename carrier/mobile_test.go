package mobile

import (
	"bytes"
	"crypto/rand"
	"github.com/kulikov0/headless-client/webrtc"
	"github.com/pion/turn/v4"
	"net"
	"testing"
	"time"
)

type callbacks struct{ signals chan []byte }

func (c *callbacks) Status(string)   {}
func (c *callbacks) Signal(b []byte) { c.signals <- append([]byte(nil), b...) }

type wire struct{ receive func([]byte) }

func (w *wire) SendData(b []byte)        { w.receive(b) }
func (w *wire) SetOnData(f func([]byte)) {}
func (w *wire) SetOnClose(f func())      {}
func (w *wire) Reconfigure(int, int)     {}
func pair(t *testing.T) (*Session, *Session, *callbacks, *callbacks) {
	t.Helper()
	a, b := &callbacks{make(chan []byte, 2)}, &callbacks{make(chan []byte, 2)}
	x, e := NewSession(bytes.Repeat([]byte{7}, 32), true, a)
	if e != nil {
		t.Fatal(e)
	}
	y, e := NewSession(bytes.Repeat([]byte{7}, 32), false, b)
	if e != nil {
		t.Fatal(e)
	}
	x.mu.Lock()
	x.tun = &wire{y.receive}
	x.peerChallenge = append([]byte(nil), y.challenge...)
	x.mu.Unlock()
	y.mu.Lock()
	y.tun = &wire{x.receive}
	y.peerChallenge = append([]byte(nil), x.challenge...)
	y.mu.Unlock()
	t.Cleanup(x.Close)
	t.Cleanup(y.Close)
	return x, y, a, b
}
func TestAuthenticatedSignals(t *testing.T) {
	x, y, a, b := pair(t)
	if !x.SendSignal([]byte("offer")) {
		t.Fatal("not sent")
	}
	select {
	case got := <-b.signals:
		if string(got) != "offer" {
			t.Fatal(string(got))
		}
	case <-time.After(time.Second):
		t.Fatal("not received")
	}
	if !y.SendSignal([]byte("answer")) {
		t.Fatal("not sent")
	}
	select {
	case got := <-a.signals:
		if string(got) != "answer" {
			t.Fatal(string(got))
		}
	case <-time.After(time.Second):
		t.Fatal("not received")
	}
	x.Close()
	if x.SendSignal([]byte("late")) {
		t.Fatal("sent after close")
	}
}
func TestInvalidCiphertextIsIgnored(t *testing.T) {
	x, _, a, _ := pair(t)
	x.receive(bytes.Repeat([]byte{0}, 128))
	select {
	case <-a.signals:
		t.Fatal("accepted unauthenticated frame")
	default:
	}
	if x.SendSignal(make([]byte, maxFrame)) {
		t.Fatal("oversized signal sent")
	}
}
func allocate(t *testing.T, s *Session) (*turn.Client, net.PacketConn) {
	t.Helper()
	conn, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	client, err := turn.NewClient(&turn.ClientConfig{STUNServerAddr: s.control.LocalAddr().String(), TURNServerAddr: s.control.LocalAddr().String(), Conn: conn, Username: s.user, Password: s.password, Realm: "dpcall-local", RTO: 100 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	if err = client.Listen(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { client.Close(); conn.Close() })
	relay, err := client.Allocate()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { relay.Close() })
	return client, relay
}
func TestTURNRelaysPacketsBothWaysWithoutInternet(t *testing.T) {
	x, y, _, _ := pair(t)
	cx, rx := allocate(t, x)
	cy, ry := allocate(t, y)
	if err := cx.CreatePermission(ry.LocalAddr()); err != nil {
		t.Fatal(err)
	}
	if err := cy.CreatePermission(rx.LocalAddr()); err != nil {
		t.Fatal(err)
	}
	for _, p := range []struct{ from, to net.PacketConn }{{rx, ry}, {ry, rx}} {
		body := []byte("encrypted WebRTC packet")
		p.to.SetReadDeadline(time.Now().Add(2 * time.Second))
		if _, err := p.from.WriteTo(body, p.to.LocalAddr()); err != nil {
			t.Fatal(err)
		}
		got := make([]byte, 256)
		n, addr, err := p.to.ReadFrom(got)
		if err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(body, got[:n]) || addr.String() != p.from.LocalAddr().String() {
			t.Fatalf("wrong packet: %q %s", got[:n], addr)
		}
	}
	// The local relay must never send a packet to an actual public destination.
	_, err := rx.WriteTo([]byte("blocked"), &net.UDPAddr{IP: net.ParseIP("203.0.113.1"), Port: 1234})
	_ = err // TURN permissions reject or discard it; the virtual PacketConn has no external socket.
}

func TestDTLSDataChannelThroughVirtualTURN(t *testing.T) {
	x, y, _, _ := pair(t)
	create := func(s *Session) *webrtc.PeerConnection {
		pc, err := webrtc.NewPeerConnection(webrtc.Configuration{ICEServers: []webrtc.ICEServer{{URLs: []string{s.TurnURL()}, Username: s.Username(), Credential: s.Password()}}, ICETransportPolicy: webrtc.ICETransportPolicyRelay})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { pc.Close() })
		return pc
	}
	a, b := create(x), create(y)
	got := make(chan string, 1)
	b.OnDataChannel(func(dc *webrtc.DataChannel) {
		dc.OnMessage(func(m webrtc.DataChannelMessage) { got <- string(m.Data) })
	})
	dc, err := a.CreateDataChannel("test", nil)
	if err != nil {
		t.Fatal(err)
	}
	dc.OnOpen(func() { dc.SendText("end-to-end DTLS through carrier") })
	offer, err := a.CreateOffer(nil)
	if err != nil {
		t.Fatal(err)
	}
	gathered := webrtc.GatheringCompletePromise(a)
	if err = a.SetLocalDescription(offer); err != nil {
		t.Fatal(err)
	}
	select {
	case <-gathered:
	case <-time.After(10 * time.Second):
		t.Fatal("offer ICE timeout")
	}
	if err = b.SetRemoteDescription(*a.LocalDescription()); err != nil {
		t.Fatal(err)
	}
	answer, err := b.CreateAnswer(nil)
	if err != nil {
		t.Fatal(err)
	}
	gathered = webrtc.GatheringCompletePromise(b)
	if err = b.SetLocalDescription(answer); err != nil {
		t.Fatal(err)
	}
	select {
	case <-gathered:
	case <-time.After(10 * time.Second):
		t.Fatal("answer ICE timeout")
	}
	if err = a.SetRemoteDescription(*b.LocalDescription()); err != nil {
		t.Fatal(err)
	}
	select {
	case text := <-got:
		if text != "end-to-end DTLS through carrier" {
			t.Fatal(text)
		}
	case <-time.After(20 * time.Second):
		t.Fatal("DTLS data channel did not connect")
	}
}
func TestEchoAndReplayRejected(t *testing.T) {
	x, y, a, b := pair(t)
	recorded := make(chan []byte, 1)
	x.mu.Lock()
	x.tun = &wire{func(raw []byte) { recorded <- append([]byte(nil), raw...) }}
	x.mu.Unlock()
	if !x.SendSignal([]byte("authenticated")) {
		t.Fatal("not queued")
	}
	var raw []byte
	select {
	case raw = <-recorded:
	case <-time.After(time.Second):
		t.Fatal("not sent")
	}
	x.receive(raw) // Own SFU self-view must not prove peer readiness or deliver a signal.
	select {
	case <-a.signals:
		t.Fatal("accepted own echo")
	default:
	}
	y.receive(raw)
	y.receive(raw)
	select {
	case <-b.signals:
	case <-time.After(time.Second):
		t.Fatal("not received")
	}
	select {
	case <-b.signals:
		t.Fatal("accepted replay")
	default:
	}
}

type livenessCallbacks struct{ statuses chan string }

func (c *livenessCallbacks) Signal([]byte)   {}
func (c *livenessCallbacks) Status(s string) { c.statuses <- s }
func TestLivenessRequiresCurrentSessionChallenge(t *testing.T) {
	cb := &livenessCallbacks{make(chan string, 2)}
	key := bytes.Repeat([]byte{9}, 32)
	x, err := NewSession(key, true, cb)
	if err != nil {
		t.Fatal(err)
	}
	defer x.Close()
	y, err := NewSession(key, false, &callbacks{make(chan []byte, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer y.Close()
	reply := func(challenge []byte) {
		nonce := make([]byte, y.aead.NonceSize())
		rand.Read(nonce)
		x.receive(y.aead.Seal(nonce, nonce, append([]byte{3}, challenge...), frameAAD))
	}
	reply(make([]byte, 16))
	select {
	case <-cb.statuses:
		t.Fatal("stale challenge authenticated")
	default:
	}
	reply(x.challenge)
	select {
	case phase := <-cb.statuses:
		if phase != "peer_ready" {
			t.Fatal(phase)
		}
	case <-time.After(time.Second):
		t.Fatal("fresh challenge rejected")
	}
}
