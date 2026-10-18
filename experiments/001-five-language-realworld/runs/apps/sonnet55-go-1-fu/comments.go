package main

import (
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

func nowUTC() time.Time { return time.Now().UTC() }

func (s *Server) commentJSON(cm *Comment, viewer uint, authors map[uint]*User) gin.H {
	u := authors[cm.AuthorID]
	var author profileJSON
	if u != nil {
		author = s.profileOf(u, viewer)
	}
	return gin.H{"id": cm.ID, "createdAt": fmtTime(cm.CreatedAt), "updatedAt": fmtTime(cm.UpdatedAt), "body": cm.Body, "author": author}
}

func (s *Server) loadAuthors(ids []uint) map[uint]*User {
	var users []User
	s.db.Where("id IN ?", ids).Find(&users)
	m := map[uint]*User{}
	for i := range users {
		m[users[i].ID] = &users[i]
	}
	return m
}

func (s *Server) listComments(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	var cms []Comment
	s.db.Where("article_id = ?", a.ID).Order("id DESC").Find(&cms)
	ids := make([]uint, len(cms))
	for i, cm := range cms {
		ids[i] = cm.AuthorID
	}
	authors := s.loadAuthors(ids)
	out := make([]gin.H, 0, len(cms))
	for i := range cms {
		out = append(out, s.commentJSON(&cms[i], viewerID(c), authors))
	}
	c.JSON(http.StatusOK, gin.H{"comments": out})
}

func (s *Server) addComment(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	in, ok := bindWrapped(c, "comment")
	if !ok {
		return
	}
	body, _, _, _ := strField(in, "body")
	if strings.TrimSpace(body) == "" {
		fail(c, http.StatusUnprocessableEntity, "body", "can't be blank")
		return
	}
	cm := &Comment{ArticleID: a.ID, AuthorID: viewerID(c), Body: body}
	if err := s.db.Create(cm).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	authors := s.loadAuthors([]uint{cm.AuthorID})
	c.JSON(http.StatusCreated, gin.H{"comment": s.commentJSON(cm, viewerID(c), authors)})
}

func (s *Server) deleteComment(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	id, err := strconv.ParseUint(c.Param("id"), 10, 64)
	var cm Comment
	if err != nil || s.db.Where("id = ? AND article_id = ?", id, a.ID).First(&cm).Error != nil {
		fail(c, http.StatusNotFound, "comment", "not found")
		return
	}
	if cm.AuthorID != viewerID(c) {
		fail(c, http.StatusForbidden, "comment", "forbidden")
		return
	}
	if err := s.db.Delete(&cm).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.Status(http.StatusNoContent)
}

func (s *Server) updateComment(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	id, err := strconv.ParseUint(c.Param("id"), 10, 64)
	var cm Comment
	if err != nil || s.db.Where("id = ? AND article_id = ?", id, a.ID).First(&cm).Error != nil {
		fail(c, http.StatusNotFound, "comment", "not found")
		return
	}
	if cm.AuthorID != viewerID(c) {
		fail(c, http.StatusForbidden, "comment", "forbidden")
		return
	}
	in, ok := bindWrapped(c, "comment")
	if !ok {
		return
	}
	body, _, _, _ := strField(in, "body")
	if strings.TrimSpace(body) == "" {
		fail(c, http.StatusUnprocessableEntity, "body", "can't be blank")
		return
	}
	// Update only the body; GORM refreshes updated_at automatically.
	if err := s.db.Model(&cm).Update("body", body).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	authors := s.loadAuthors([]uint{cm.AuthorID})
	c.JSON(http.StatusOK, gin.H{"comment": s.commentJSON(&cm, viewerID(c), authors)})
}
