package app

import (
	"crypto/rand"
	"fmt"
	"regexp"
	"sync"
	"time"
)

var uuidRe = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

func isUUID(s string) bool { return uuidRe.MatchString(s) }

func newUUID() string {
	b := make([]byte, 16)
	rand.Read(b)
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// nullUUID maps invalid input to nil so lookups return "not found" instead of a cast error.
func nullUUID(s string) any {
	if isUUID(s) {
		return s
	}
	return nil
}

// limiter is a fixed-window counter per key. It lives in memory because the
// service is a single process; a restart only resets the windows.
type limiter struct {
	mu   sync.Mutex
	hits map[string]*window
}

type window struct {
	start time.Time
	count int
}

func newLimiter() *limiter {
	l := &limiter{hits: map[string]*window{}}
	go func() {
		for range time.Tick(10 * time.Minute) {
			l.mu.Lock()
			for k, w := range l.hits {
				if time.Since(w.start) > 2*time.Hour {
					delete(l.hits, k)
				}
			}
			l.mu.Unlock()
		}
	}()
	return l
}

func (l *limiter) allow(key string, max int, per time.Duration) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	w := l.hits[key]
	if w == nil || time.Since(w.start) > per {
		w = &window{start: time.Now()}
		l.hits[key] = w
	}
	w.count++
	return w.count <= max
}
