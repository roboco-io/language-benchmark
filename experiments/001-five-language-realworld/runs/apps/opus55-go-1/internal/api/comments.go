package api

import (
	"errors"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"gorm.io/gorm"

	"realworld/internal/db"
)

type commentResponse struct {
	ID        uint            `json:"id"`
	CreatedAt string          `json:"createdAt"`
	UpdatedAt string          `json:"updatedAt"`
	Body      string          `json:"body"`
	Author    profileResponse `json:"author"`
}

func (s *Server) buildComments(comments []db.Comment, viewer *db.User) ([]commentResponse, error) {
	out := make([]commentResponse, 0, len(comments))
	if len(comments) == 0 {
		return out, nil
	}
	authorIDs := make([]uint, 0, len(comments))
	for _, cm := range comments {
		authorIDs = append(authorIDs, cm.AuthorID)
	}
	var authors []db.User
	if err := s.db.Where("id IN ?", authorIDs).Find(&authors).Error; err != nil {
		return nil, err
	}
	byID := make(map[uint]*db.User, len(authors))
	for i := range authors {
		byID[authors[i].ID] = &authors[i]
	}
	following := map[uint]bool{}
	if viewer != nil {
		var ids []uint
		if err := s.db.Model(&db.Follow{}).Where("follower_id = ? AND followee_id IN ?", viewer.ID, authorIDs).
			Pluck("followee_id", &ids).Error; err != nil {
			return nil, err
		}
		for _, id := range ids {
			following[id] = true
		}
	}
	for _, cm := range comments {
		author := byID[cm.AuthorID]
		if author == nil {
			author = &db.User{ID: cm.AuthorID}
		}
		out = append(out, commentResponse{
			ID:        cm.ID,
			CreatedAt: formatTime(cm.CreatedAt),
			UpdatedAt: formatTime(cm.UpdatedAt),
			Body:      cm.Body,
			Author:    toProfile(author, following[cm.AuthorID]),
		})
	}
	return out, nil
}

func (s *Server) listComments(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	var comments []db.Comment
	if err := s.db.Where("article_id = ?", a.ID).Order("created_at DESC, id DESC").Find(&comments).Error; err != nil {
		internalError(c, err)
		return
	}
	res, err := s.buildComments(comments, currentUser(c))
	if err != nil {
		internalError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"comments": res})
}

func (s *Server) addComment(c *gin.Context) {
	var req struct {
		Comment struct {
			Body Optional[string] `json:"body"`
		} `json:"comment"`
	}
	if !bindJSON(c, &req) {
		return
	}
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	errs := fieldErrors{}
	requireString(errs, "body", req.Comment.Body)
	if len(errs) > 0 {
		abortErrors(c, http.StatusUnprocessableEntity, errs)
		return
	}
	cm := db.Comment{Body: req.Comment.Body.Value, ArticleID: a.ID, AuthorID: currentUser(c).ID}
	if err := s.db.Create(&cm).Error; err != nil {
		internalError(c, err)
		return
	}
	res, err := s.buildComments([]db.Comment{cm}, currentUser(c))
	if err != nil {
		internalError(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"comment": res[0]})
}

func (s *Server) deleteComment(c *gin.Context) {
	a, ok := s.findArticle(c)
	if !ok {
		return
	}
	id, err := strconv.ParseUint(c.Param("id"), 10, 64)
	if err != nil {
		notFound(c, "comment")
		return
	}
	var cm db.Comment
	err = s.db.Where("id = ? AND article_id = ?", id, a.ID).Take(&cm).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		notFound(c, "comment")
		return
	}
	if err != nil {
		internalError(c, err)
		return
	}
	if cm.AuthorID != currentUser(c).ID {
		forbidden(c, "comment")
		return
	}
	if err := s.db.Delete(&db.Comment{}, cm.ID).Error; err != nil {
		internalError(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}
