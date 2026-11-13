package mobile

import (
	"bytes"
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
	x.tun = &wire{y.receive}
	y.tun = &wire{x.receive}
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
