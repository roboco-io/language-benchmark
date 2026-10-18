package main

import (
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
)

type commentResponse struct {
	ID        uint            `json:"id"`
	CreatedAt string          `json:"createdAt"`
	UpdatedAt string          `json:"updatedAt"`
	Body      string          `json:"body"`
	Author    profileResponse `json:"author"`
}

func (s *Server) buildComments(comments []Comment, viewer *User) []commentResponse {
	out := make([]commentResponse, 0, len(comments))
	if len(comments) == 0 {
		return out
	}
	authorIDs := make([]uint, 0, len(comments))
	for _, cm := range comments {
		authorIDs = append(authorIDs, cm.AuthorID)
	}
	var authors []User
	s.db.Where("id IN ?", authorIDs).Find(&authors)
	authorByID := make(map[uint]*User, len(authors))
	for i := range authors {
		authorByID[authors[i].ID] = &authors[i]
	}
	following := make(map[uint]bool)
	if viewer != nil {
		var ids []uint
		s.db.Model(&Follow{}).Where("follower_id = ? AND followee_id IN ?", viewer.ID, authorIDs).Pluck("followee_id", &ids)
		for _, id := range ids {
			following[id] = true
		}
	}
	for _, cm := range comments {
		var author profileResponse
		if u := authorByID[cm.AuthorID]; u != nil {
			author = toProfile(u, following[u.ID])
		}
		out = append(out, commentResponse{
			ID:        cm.ID,
			CreatedAt: fmtTime(cm.CreatedAt),
			UpdatedAt: fmtTime(cm.UpdatedAt),
			Body:      cm.Body,
			Author:    author,
		})
	}
	return out
}

func (s *Server) listComments(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	var comments []Comment
	s.db.Where("article_id = ?", a.ID).Order("created_at ASC, id ASC").Find(&comments)
	c.JSON(200, gin.H{"comments": s.buildComments(comments, currentUser(c))})
}

func (s *Server) createComment(c *gin.Context) {
	in, ok := bindEnvelope(c, "comment")
	if !ok {
		return
	}
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	body := in.str("body")
	if strings.TrimSpace(body) == "" {
		abortErr(c, 422, "body", "can't be blank")
		return
	}
	me := currentUser(c)
	cm := Comment{Body: body, ArticleID: a.ID, AuthorID: me.ID}
	if err := s.db.Create(&cm).Error; err != nil {
		abortErr(c, 500, "comment", "could not be created")
		return
	}
	c.JSON(201, gin.H{"comment": s.buildComments([]Comment{cm}, me)[0]})
}

func (s *Server) deleteComment(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	id, err := strconv.ParseUint(c.Param("id"), 10, 64)
	var cm Comment
	if err != nil || s.db.Where("id = ? AND article_id = ?", id, a.ID).First(&cm).Error != nil {
		abortErr(c, 404, "comment", "not found")
		return
	}
	if cm.AuthorID != currentUser(c).ID {
		abortErr(c, 403, "comment", "forbidden")
		return
	}
	s.db.Delete(&Comment{}, cm.ID)
	c.Status(204)
}

func (s *Server) updateComment(c *gin.Context) {
	in, ok := bindEnvelope(c, "comment")
	if !ok {
		return
	}
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	id, err := strconv.ParseUint(c.Param("id"), 10, 64)
	var cm Comment
	if err != nil || s.db.Where("id = ? AND article_id = ?", id, a.ID).First(&cm).Error != nil {
		abortErr(c, 404, "comment", "not found")
		return
	}
	me := currentUser(c)
	if cm.AuthorID != me.ID {
		abortErr(c, 403, "comment", "forbidden")
		return
	}
	body := in.str("body")
	if strings.TrimSpace(body) == "" {
		abortErr(c, 422, "body", "can't be blank")
		return
	}
	// Update only the body; GORM refreshes updated_at automatically.
	if err := s.db.Model(&cm).Update("body", body).Error; err != nil {
		abortErr(c, 500, "comment", "could not be updated")
		return
	}
	s.db.First(&cm, cm.ID)
	c.JSON(200, gin.H{"comment": s.buildComments([]Comment{cm}, me)[0]})
}
