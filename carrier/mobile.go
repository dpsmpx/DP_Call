// DP Call carrier adapter. Upstream SFU clients are MIT licensed; see UPSTREAM.md.
package mobile

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"time"

	"github.com/pion/turn/v4"
	"whitelist-bypass/relay/pion"
	joiner "whitelist-bypass/relay/pion/headless-joiner-common"
	"whitelist-bypass/relay/tunnel"
)

const maxFrame = 65536

var frameAAD = []byte("dpcall-carrier-v1")

type Callbacks interface {
	Status(string)
	Signal([]byte)
}

type Session struct {
	mu       sync.Mutex
	cb       Callbacks
	aead     cipher.AEAD
	tun      tunnel.DataTunnel
	closer   interface{ Close() }
	server   *turn.Server
	control  net.PacketConn
	relays   map[int]*packetConn
	nextPort int
	localIP  net.IP
	remoteIP net.IP
	user     string
	password string
	closed   bool
	stop     chan struct{}
}

// NewSession only opens a loopback TURN listener. Start joins the remote room.
func NewSession(key []byte, first bool, cb Callbacks) (*Session, error) {
	if len(key) != 32 || cb == nil {
		return nil, errors.New("invalid carrier configuration")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	s := &Session{cb: cb, aead: aead, relays: map[int]*packetConn{}, nextPort: 40000, stop: make(chan struct{})}
	s.localIP = net.ParseIP("198.18.0.1")
	s.remoteIP = net.ParseIP("198.18.0.2")
	if !first {
		s.localIP, s.remoteIP = s.remoteIP, s.localIP
	}
	credentials := make([]byte, 32)
	if _, err = rand.Read(credentials); err != nil {
		return nil, err
	}
	s.user = fmt.Sprintf("%x", credentials[:8])
	s.password = fmt.Sprintf("%x", credentials[8:])
	s.control, err = net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	s.server, err = turn.NewServer(turn.ServerConfig{
		Realm: "dpcall-local",
		AuthHandler: func(user, realm string, src net.Addr) ([]byte, bool) {
			return turn.GenerateAuthKey(s.user, realm, s.password), user == s.user
		},
		PacketConnConfigs: []turn.PacketConnConfig{{PacketConn: s.control, RelayAddressGenerator: s}},
	})
	if err != nil {
		s.control.Close()
		return nil, err
	}
	return s, nil
}
func (s *Session) TurnURL() string {
	return fmt.Sprintf("turn:127.0.0.1:%d?transport=udp", s.control.LocalAddr().(*net.UDPAddr).Port)
}
func (s *Session) Username() string { return s.user }
func (s *Session) Password() string { return s.password }
func (s *Session) EmitStatus(status string) {
	s.mu.Lock()
	closed := s.closed
	s.mu.Unlock()
	if !closed {
		s.cb.Status(status)
	}
}
func (s *Session) EmitStatusError(_ string)    { s.EmitStatus("failed") }
func (s *Session) Start(provider, room string) { go s.run(provider, room) }
func (s *Session) run(provider, room string) {
	logFn := func(string, ...any) {} // Upstream logs contain room links and credentials.
	connected := func(t tunnel.DataTunnel) {
		s.mu.Lock()
		if s.closed {
			s.mu.Unlock()
			return
		}
		s.tun = t
		s.mu.Unlock()
		t.SetOnData(s.receive)
		t.SetOnClose(func() { s.EmitStatus("reconnecting") })
		s.EmitStatus("room_connected")
		// A room connection alone does not prove the other phone is reachable.
		go func() {
			ticker := time.NewTicker(time.Second)
			defer ticker.Stop()
			for {
				select {
				case <-s.stop:
					return
				case <-ticker.C:
					s.send(0, nil)
				}
			}
		}()
	}
	switch provider {
	case "telemost":
		j := joiner.NewTelemostHeadlessJoiner(logFn, nil, s, nil, pion.AddTunnelTracks, pion.ReadTrack)
		j.MarkConfigAcked()
		j.OnConnected = connected
		s.mu.Lock()
		if s.closed {
			s.mu.Unlock()
			return
		}
		s.closer = j
		s.mu.Unlock()
		params, _ := json.Marshal(map[string]any{"joinLink": room, "displayName": "DP Call", "vp8Fps": 24, "vp8Batch": 2, "reliable": false})
		j.RunWithParams(string(params))
	case "vk":
		auth, err := joiner.RunVKAuth(room, "DP Call", logFn, s.EmitStatus, nil, nil)
		if err != nil {
			s.EmitStatus("failed")
			return
		}
		var params map[string]any
		if json.Unmarshal([]byte(auth), &params) != nil {
			s.EmitStatus("failed")
			return
		}
		// Both peers must derive the upstream frame key from the same original URL.
		params["tunnelMode"] = "video"
		params["vp8Fps"] = 24
		params["vp8Batch"] = 2
		params["reliable"] = false
		j := joiner.NewVKHeadlessJoiner(logFn, nil, s, nil, pion.AddTunnelTracks, pion.ReadTrack)
		j.MarkConfigAcked()
		j.OnConnected = connected
		s.mu.Lock()
		if s.closed {
			s.mu.Unlock()
			j.Close()
			return
		}
		s.closer = j
		s.mu.Unlock()
		raw, _ := json.Marshal(params)
		j.RunWithParams(string(raw))
	default:
		s.EmitStatus("failed")
	}
}
func (s *Session) send(kind byte, body []byte) bool {
	if len(body) > maxFrame-64 {
		return false
	}
	nonce := make([]byte, s.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return false
	}
	plain := append([]byte{kind}, body...)
	sealed := s.aead.Seal(nonce, nonce, plain, frameAAD)
	s.mu.Lock()
	t := s.tun
	closed := s.closed
	s.mu.Unlock()
	if t == nil || closed {
		return false
	}
	t.SendData(sealed)
	return true
}
func (s *Session) SendSignal(data []byte) bool { return s.send(1, data) }
func (s *Session) receive(raw []byte) {
	if len(raw) < s.aead.NonceSize()+s.aead.Overhead()+1 || len(raw) > maxFrame {
		return
	}
	plain, err := s.aead.Open(nil, raw[:s.aead.NonceSize()], raw[s.aead.NonceSize():], frameAAD)
	if err != nil {
		return
	}
	s.mu.Lock()
	closed := s.closed
	s.mu.Unlock()
	if closed {
		return
	}
	switch plain[0] {
	case 0:
		s.EmitStatus("peer_ready")
	case 1:
		s.cb.Signal(plain[1:])
	case 2:
		if len(plain) < 5 {
			return
		}
		src := int(binary.BigEndian.Uint16(plain[1:3]))
		dst := int(binary.BigEndian.Uint16(plain[3:5]))
		s.mu.Lock()
		p := s.relays[dst]
		s.mu.Unlock()
		if p != nil {
			select {
			case p.in <- datagram{append([]byte(nil), plain[5:]...), &net.UDPAddr{IP: s.remoteIP, Port: src}}:
			default:
			}
		}
	}
}
func (s *Session) Close() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	close(s.stop)
	closer := s.closer
	conns := make([]*packetConn, 0, len(s.relays))
	for _, p := range s.relays {
		conns = append(conns, p)
	}
	s.mu.Unlock()
	joiner.StopCaptchaProxy()
	if closer != nil {
		closer.Close()
	}
	if s.server != nil {
		s.server.Close()
	}
	if s.control != nil {
		s.control.Close()
	}
	for _, p := range conns {
		p.Close()
	}
}
func (s *Session) Validate() error { return nil }
func (s *Session) AllocatePacketConn(network string, requestedPort int) (net.PacketConn, net.Addr, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil, nil, net.ErrClosed
	}
	s.nextPort++
	p := &packetConn{s: s, port: s.nextPort, in: make(chan datagram, 256), done: make(chan struct{})}
	s.relays[p.port] = p
	return p, p.LocalAddr(), nil
}
func (s *Session) AllocateConn(network string, requestedPort int) (net.Conn, net.Addr, error) {
	return nil, nil, errors.New("TCP allocation unsupported")
}

type datagram struct {
	body []byte
	addr net.Addr
}
type packetConn struct {
	s    *Session
	port int
	in   chan datagram
	done chan struct{}
	once sync.Once
}

func (p *packetConn) ReadFrom(b []byte) (int, net.Addr, error) {
	select {
	case <-p.done:
		return 0, nil, net.ErrClosed
	case d := <-p.in:
		return copy(b, d.body), d.addr, nil
	}
}
func (p *packetConn) WriteTo(b []byte, addr net.Addr) (int, error) {
	dst, ok := addr.(*net.UDPAddr)
	if !ok || !dst.IP.Equal(p.s.remoteIP) {
		return 0, errors.New("destination is outside carrier")
	}
	buf := make([]byte, 4+len(b))
	binary.BigEndian.PutUint16(buf[:2], uint16(p.port))
	binary.BigEndian.PutUint16(buf[2:4], uint16(dst.Port))
	copy(buf[4:], b)
	if !p.s.send(2, buf) {
		return 0, io.ErrClosedPipe
	}
	return len(b), nil
}
func (p *packetConn) Close() error {
	p.once.Do(func() { close(p.done); p.s.mu.Lock(); delete(p.s.relays, p.port); p.s.mu.Unlock() })
	return nil
}
func (p *packetConn) LocalAddr() net.Addr                { return &net.UDPAddr{IP: p.s.localIP, Port: p.port} }
func (p *packetConn) SetDeadline(t time.Time) error      { return nil }
func (p *packetConn) SetReadDeadline(t time.Time) error  { return nil }
func (p *packetConn) SetWriteDeadline(t time.Time) error { return nil }
