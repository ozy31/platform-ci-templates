package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRoutes(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name       string
		method     string
		path       string
		wantStatus int
		wantType   string
	}{
		{"health", http.MethodGet, "/healthz", http.StatusOK, "text/plain; charset=utf-8"},
		{"version", http.MethodGet, "/version", http.StatusOK, "application/json"},
		{"root", http.MethodGet, "/", http.StatusOK, "application/json"},
		{"unknown path", http.MethodGet, "/nope", http.StatusNotFound, ""},
		{"wrong method", http.MethodPost, "/healthz", http.StatusMethodNotAllowed, ""},
	}

	mux := newMux()
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()
			rec := httptest.NewRecorder()
			mux.ServeHTTP(rec, httptest.NewRequest(tc.method, tc.path, nil))

			if rec.Code != tc.wantStatus {
				t.Fatalf("status = %d, want %d", rec.Code, tc.wantStatus)
			}
			if tc.wantType != "" && rec.Header().Get("Content-Type") != tc.wantType {
				t.Fatalf("content-type = %q, want %q", rec.Header().Get("Content-Type"), tc.wantType)
			}
		})
	}
}

func TestVersionPayload(t *testing.T) {
	t.Parallel()

	rec := httptest.NewRecorder()
	newMux().ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/version", nil))

	var body map[string]string
	if err := json.NewDecoder(rec.Body).Decode(&body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if body["version"] != version {
		t.Fatalf("version = %q, want %q", body["version"], version)
	}
}
