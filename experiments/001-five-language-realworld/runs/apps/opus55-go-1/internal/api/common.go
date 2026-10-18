package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

// Optional distinguishes an absent JSON key from an explicit null and a value.
type Optional[T any] struct {
	Set   bool
	Null  bool
	Value T
}

func (o *Optional[T]) UnmarshalJSON(b []byte) error {
	o.Set = true
	if bytes.Equal(bytes.TrimSpace(b), []byte("null")) {
		o.Null = true
		return nil
	}
	return json.Unmarshal(b, &o.Value)
}

// fieldErrors maps a field name to its validation messages.
type fieldErrors map[string][]string

func (f fieldErrors) add(field, msg string) { f[field] = append(f[field], msg) }

func abortErrors(c *gin.Context, status int, errs fieldErrors) {
	c.AbortWithStatusJSON(status, gin.H{"errors": errs})
}

func abortError(c *gin.Context, status int, field, msg string) {
	abortErrors(c, status, fieldErrors{field: {msg}})
}

func notFound(c *gin.Context, field string)  { abortError(c, http.StatusNotFound, field, "not found") }
func forbidden(c *gin.Context, field string) { abortError(c, http.StatusForbidden, field, "forbidden") }

func internalError(c *gin.Context, err error) {
	_ = c.Error(err)
	abortError(c, http.StatusInternalServerError, "server", err.Error())
}

// bindJSON decodes the request body into dst, replying 422 on malformed input.
func bindJSON(c *gin.Context, dst any) bool {
	dec := json.NewDecoder(c.Request.Body)
	if err := dec.Decode(dst); err != nil {
		abortError(c, http.StatusUnprocessableEntity, "body", "is invalid")
		return false
	}
	return true
}

// requireString records a "can't be blank" error when a required field is
// absent, null, or blank.
func requireString(errs fieldErrors, field string, o Optional[string]) {
	if !o.Set || o.Null || strings.TrimSpace(o.Value) == "" {
		errs.add(field, "can't be blank")
	}
}

// formatTime renders timestamps as ISO 8601 with millisecond precision.
func formatTime(t time.Time) string {
	return t.UTC().Format("2006-01-02T15:04:05.000Z")
}

// nullable turns an empty string into nil so it serializes as JSON null.
func nullable(s *string) *string {
	if s == nil || *s == "" {
		return nil
	}
	return s
}
