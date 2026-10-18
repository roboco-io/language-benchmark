package main

import (
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"gorm.io/gorm"
)

type commentJSON struct {
	ID        uint        `json:"id"`
	CreatedAt string      `json:"createdAt"`
	UpdatedAt string      `json:"updatedAt"`
	Body      string      `json:"body"`
	Author    profileJSON `json:"author"`
}

func (a *App) buildComments(viewer *User, comments []Comment) ([]commentJSON, error) {
	out := make([]commentJSON, 0, len(comments))
	if len(comments) == 0 {
		return out, nil
	}
	authorIDs := make([]uint, len(comments))
	for i, cm := range comments {
		authorIDs[i] = cm.AuthorID
	}
	var authors []User
	if err := a.DB.Where("id IN ?", authorIDs).Find(&authors).Error; err != nil {
		return nil, err
	}
	byID := make(map[uint]*User, len(authors))
	for i := range authors {
		byID[authors[i].ID] = &authors[i]
	}
	following := map[uint]bool{}
	if viewer != nil {
		var ids []uint
		if err := a.DB.Model(&Follow{}).Where("follower_id = ? AND followee_id IN ?", viewer.ID, authorIDs).
			Pluck("followee_id", &ids).Error; err != nil {
			return nil, err
		}
		for _, id := range ids {
			following[id] = true
		}
	}
	for _, cm := range comments {
		j := commentJSON{ID: cm.ID, Body: cm.Body, CreatedAt: formatTime(cm.CreatedAt), UpdatedAt: formatTime(cm.UpdatedAt)}
		if u := byID[cm.AuthorID]; u != nil {
			j.Author = profileJSON{Username: u.Username, Bio: u.Bio, Image: u.Image, Following: following[u.ID]}
		}
		out = append(out, j)
	}
	return out, nil
}

func (a *App) listComments(c *gin.Context) {
	art, ok := a.findArticle(c)
	if !ok {
		return
	}
	var comments []Comment
	if err := a.DB.Where("article_id = ?", art.ID).Order("id").Find(&comments).Error; err != nil {
		internalError(c, err)
		return
	}
	list, err := a.buildComments(current(c), comments)
	if err != nil {
		internalError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"comments": list})
}

func (a *App) createComment(c *gin.Context) {
	art, ok := a.findArticle(c)
	if !ok {
		return
	}
	props, ok := readEnvelope(c, "comment")
	if !ok {
		return
	}
	body, valid := props["body"].str()
	if !valid {
		errorJSON(c, http.StatusUnprocessableEntity, "body", "must be a string")
		return
	}
	if strings.TrimSpace(body) == "" {
		errorJSON(c, http.StatusUnprocessableEntity, "body", "can't be blank")
		return
	}
	now := time.Now().UTC()
	cm := Comment{Body: body, ArticleID: art.ID, AuthorID: current(c).ID, CreatedAt: now, UpdatedAt: now}
	if err := a.DB.Create(&cm).Error; err != nil {
		internalError(c, err)
		return
	}
	list, err := a.buildComments(current(c), []Comment{cm})
	if err != nil {
		internalError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"comment": list[0]})
}

func (a *App) deleteComment(c *gin.Context) {
	art, ok := a.findArticle(c)
	if !ok {
		return
	}
	id, err := strconv.ParseUint(c.Param("id"), 10, 64)
	if err != nil {
		errorJSON(c, http.StatusNotFound, "comment", "not found")
		return
	}
	var cm Comment
	err = a.DB.Where("id = ? AND article_id = ?", id, art.ID).First(&cm).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		errorJSON(c, http.StatusNotFound, "comment", "not found")
		return
	}
	if err != nil {
		internalError(c, err)
		return
	}
	if cm.AuthorID != current(c).ID {
		errorJSON(c, http.StatusForbidden, "comment", "forbidden")
		return
	}
	if err := a.DB.Delete(&Comment{}, cm.ID).Error; err != nil {
		internalError(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}
