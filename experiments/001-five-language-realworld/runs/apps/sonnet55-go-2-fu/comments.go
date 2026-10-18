package main

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
)

func (a *App) commentJSON(cm *Comment, author *User, following bool) gin.H {
	return gin.H{
		"id": cm.ID, "createdAt": fmtTime(cm.CreatedAt), "updatedAt": fmtTime(cm.UpdatedAt), "body": cm.Body,
		"author": gin.H{"username": author.Username, "bio": author.Bio, "image": author.Image, "following": following},
	}
}

func (a *App) listComments(c *gin.Context) {
	ar, ok := a.findArticle(c)
	if !ok {
		return
	}
	var cms []Comment
	a.db.Where("article_id = ?", ar.ID).Order("created_at ASC, id ASC").Find(&cms)

	authors := map[uint]User{}
	ids := []uint{}
	for _, cm := range cms {
		if _, seen := authors[cm.AuthorID]; !seen {
			authors[cm.AuthorID] = User{}
			ids = append(ids, cm.AuthorID)
		}
	}
	var us []User
	if len(ids) > 0 {
		a.db.Where("id IN ?", ids).Find(&us)
	}
	for _, u := range us {
		authors[u.ID] = u
	}
	following := map[uint]bool{}
	if me := viewerID(c); me != 0 && len(ids) > 0 {
		var fids []uint
		a.db.Model(&Follow{}).Where("follower_id = ? AND followee_id IN ?", me, ids).Pluck("followee_id", &fids)
		for _, id := range fids {
			following[id] = true
		}
	}
	out := make([]gin.H, 0, len(cms))
	for i := range cms {
		au := authors[cms[i].AuthorID]
		out = append(out, a.commentJSON(&cms[i], &au, following[au.ID]))
	}
	c.JSON(http.StatusOK, gin.H{"comments": out})
}

func (a *App) addComment(c *gin.Context) {
	ar, ok := a.findArticle(c)
	if !ok {
		return
	}
	f, ok := bindWrapped(c, "comment")
	if !ok {
		return
	}
	body, _, _ := strField(f, "body")
	if blank(body) {
		fail(c, http.StatusUnprocessableEntity, "body", "can't be blank")
		return
	}
	me := viewer(c)
	cm := Comment{ArticleID: ar.ID, AuthorID: me.ID, Body: body}
	if err := a.db.Create(&cm).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.JSON(http.StatusCreated, gin.H{"comment": a.commentJSON(&cm, me, false)})
}

func (a *App) deleteComment(c *gin.Context) {
	ar, ok := a.findArticle(c)
	if !ok {
		return
	}
	id, err := strconv.ParseUint(c.Param("id"), 10, 64)
	var cm Comment
	if err != nil {
		fail(c, http.StatusNotFound, "comment", "not found")
		return
	}
	res := a.db.Where("id = ? AND article_id = ?", id, ar.ID).Limit(1).Find(&cm)
	if res.Error != nil || res.RowsAffected == 0 {
		fail(c, http.StatusNotFound, "comment", "not found")
		return
	}
	if cm.AuthorID != viewerID(c) {
		fail(c, http.StatusForbidden, "comment", "forbidden")
		return
	}
	if err := a.db.Delete(&Comment{}, cm.ID).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.Status(http.StatusNoContent)
}

func (a *App) updateComment(c *gin.Context) {
	ar, ok := a.findArticle(c)
	if !ok {
		return
	}
	id, err := strconv.ParseUint(c.Param("id"), 10, 64)
	if err != nil {
		fail(c, http.StatusNotFound, "comment", "not found")
		return
	}
	var cm Comment
	res := a.db.Where("id = ? AND article_id = ?", id, ar.ID).Limit(1).Find(&cm)
	if res.Error != nil || res.RowsAffected == 0 {
		fail(c, http.StatusNotFound, "comment", "not found")
		return
	}
	me := viewer(c)
	if cm.AuthorID != me.ID {
		fail(c, http.StatusForbidden, "comment", "forbidden")
		return
	}
	f, ok := bindWrapped(c, "comment")
	if !ok {
		return
	}
	body, _, _ := strField(f, "body")
	if blank(body) {
		fail(c, http.StatusUnprocessableEntity, "body", "can't be blank")
		return
	}
	cm.Body = body
	if err := a.db.Save(&cm).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.JSON(http.StatusOK, gin.H{"comment": a.commentJSON(&cm, me, false)})
}
