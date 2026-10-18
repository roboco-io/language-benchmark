package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"gorm.io/gorm"
)

type articleJSON struct {
	Slug           string      `json:"slug"`
	Title          string      `json:"title"`
	Description    string      `json:"description"`
	Body           *string     `json:"body,omitempty"`
	TagList        []string    `json:"tagList"`
	CreatedAt      string      `json:"createdAt"`
	UpdatedAt      string      `json:"updatedAt"`
	Favorited      bool        `json:"favorited"`
	FavoritesCount int64       `json:"favoritesCount"`
	Author         profileJSON `json:"author"`
}

// buildArticles renders articles with batched queries for authors, tags,
// favorite counts and viewer-specific flags. List endpoints omit the body.
func (a *App) buildArticles(viewer *User, arts []Article, withBody bool) ([]articleJSON, error) {
	out := make([]articleJSON, 0, len(arts))
	if len(arts) == 0 {
		return out, nil
	}
	ids := make([]uint, len(arts))
	authorIDs := make([]uint, 0, len(arts))
	for i, art := range arts {
		ids[i] = art.ID
		authorIDs = append(authorIDs, art.AuthorID)
	}

	var authors []User
	if err := a.DB.Where("id IN ?", authorIDs).Find(&authors).Error; err != nil {
		return nil, err
	}
	authorByID := make(map[uint]*User, len(authors))
	for i := range authors {
		authorByID[authors[i].ID] = &authors[i]
	}

	var tagRows []struct {
		ArticleID uint
		Name      string
	}
	if err := a.DB.Table("article_tags").Select("article_tags.article_id, tags.name").
		Joins("JOIN tags ON tags.id = article_tags.tag_id").
		Where("article_tags.article_id IN ?", ids).
		Order("article_tags.article_id, article_tags.position").Scan(&tagRows).Error; err != nil {
		return nil, err
	}
	tagsByID := map[uint][]string{}
	for _, r := range tagRows {
		tagsByID[r.ArticleID] = append(tagsByID[r.ArticleID], r.Name)
	}

	var countRows []struct {
		ArticleID uint
		N         int64
	}
	if err := a.DB.Model(&Favorite{}).Select("article_id, COUNT(*) AS n").
		Where("article_id IN ?", ids).Group("article_id").Scan(&countRows).Error; err != nil {
		return nil, err
	}
	countByID := map[uint]int64{}
	for _, r := range countRows {
		countByID[r.ArticleID] = r.N
	}

	favorited := map[uint]bool{}
	following := map[uint]bool{}
	if viewer != nil {
		var favIDs []uint
		if err := a.DB.Model(&Favorite{}).Where("user_id = ? AND article_id IN ?", viewer.ID, ids).
			Pluck("article_id", &favIDs).Error; err != nil {
			return nil, err
		}
		for _, id := range favIDs {
			favorited[id] = true
		}
		var followIDs []uint
		if err := a.DB.Model(&Follow{}).Where("follower_id = ? AND followee_id IN ?", viewer.ID, authorIDs).
			Pluck("followee_id", &followIDs).Error; err != nil {
			return nil, err
		}
		for _, id := range followIDs {
			following[id] = true
		}
	}

	for _, art := range arts {
		author := authorByID[art.AuthorID]
		tags := tagsByID[art.ID]
		if tags == nil {
			tags = []string{}
		}
		j := articleJSON{
			Slug: art.Slug, Title: art.Title, Description: art.Description, TagList: tags,
			CreatedAt: formatTime(art.CreatedAt), UpdatedAt: formatTime(art.UpdatedAt),
			Favorited: favorited[art.ID], FavoritesCount: countByID[art.ID],
		}
		if withBody {
			body := art.Body
			j.Body = &body
		}
		if author != nil {
			j.Author = profileJSON{Username: author.Username, Bio: author.Bio, Image: author.Image, Following: following[author.ID]}
		}
		out = append(out, j)
	}
	return out, nil
}

func (a *App) respondArticle(c *gin.Context, status int, art *Article) {
	list, err := a.buildArticles(current(c), []Article{*art}, true)
	if err != nil {
		internalError(c, err)
		return
	}
	c.JSON(status, gin.H{"article": list[0]})
}

// pagination reads limit (default 20, max 100) and offset (default 0).
func pagination(c *gin.Context) (int, int) {
	limit, err := strconv.Atoi(c.Query("limit"))
	if err != nil || limit < 0 {
		limit = 20
	}
	if limit > 100 {
		limit = 100
	}
	offset, err := strconv.Atoi(c.Query("offset"))
	if err != nil || offset < 0 {
		offset = 0
	}
	return limit, offset
}

func (a *App) respondArticleList(c *gin.Context, q *gorm.DB) {
	var total int64
	if err := q.Session(&gorm.Session{}).Count(&total).Error; err != nil {
		internalError(c, err)
		return
	}
	limit, offset := pagination(c)
	var arts []Article
	if err := q.Order("articles.id DESC").Limit(limit).Offset(offset).Find(&arts).Error; err != nil {
		internalError(c, err)
		return
	}
	list, err := a.buildArticles(current(c), arts, false)
	if err != nil {
		internalError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"articles": list, "articlesCount": total})
}

func (a *App) listArticles(c *gin.Context) {
	q := a.DB.Model(&Article{})
	if tag := c.Query("tag"); tag != "" {
		q = q.Where("articles.id IN (SELECT at.article_id FROM article_tags at JOIN tags t ON t.id = at.tag_id WHERE t.name = ?)", tag)
	}
	if author := c.Query("author"); author != "" {
		q = q.Where("articles.author_id IN (SELECT id FROM users WHERE username = ?)", author)
	}
	if fav := c.Query("favorited"); fav != "" {
		q = q.Where("articles.id IN (SELECT f.article_id FROM favorites f JOIN users u ON u.id = f.user_id WHERE u.username = ?)", fav)
	}
	a.respondArticleList(c, q)
}

func (a *App) feed(c *gin.Context) {
	q := a.DB.Model(&Article{}).
		Where("articles.author_id IN (SELECT followee_id FROM follows WHERE follower_id = ?)", current(c).ID)
	a.respondArticleList(c, q)
}

func (a *App) findArticle(c *gin.Context) (*Article, bool) {
	var art Article
	err := a.DB.Where("slug = ?", c.Param("slug")).First(&art).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		errorJSON(c, http.StatusNotFound, "article", "not found")
		return nil, false
	}
	if err != nil {
		internalError(c, err)
		return nil, false
	}
	return &art, true
}

func (a *App) getArticle(c *gin.Context) {
	if art, ok := a.findArticle(c); ok {
		a.respondArticle(c, http.StatusOK, art)
	}
}

var nonSlugChars = regexp.MustCompile(`[^a-z0-9]+`)

func slugify(title string) string {
	s := strings.Trim(nonSlugChars.ReplaceAllString(strings.ToLower(title), "-"), "-")
	if s == "" {
		s = "article"
	}
	return s
}

// uniqueSlug derives a slug from the title and appends a random suffix when
// the plain slug is already used by another article.
func (a *App) uniqueSlug(tx *gorm.DB, title string, exceptID uint) (string, error) {
	base := slugify(title)
	slug := base
	for range 10 {
		var n int64
		if err := tx.Model(&Article{}).Where("slug = ? AND id <> ?", slug, exceptID).Count(&n).Error; err != nil {
			return "", err
		}
		if n == 0 {
			return slug, nil
		}
		buf := make([]byte, 4)
		_, _ = rand.Read(buf)
		slug = base + "-" + hex.EncodeToString(buf)
	}
	return "", errors.New("could not generate a unique slug")
}

// parseTagList decodes a tagList property. It trims names, drops empty ones
// and removes duplicates while keeping the original order.
func parseTagList(f field) ([]string, bool) {
	var raw []string
	if err := json.Unmarshal(f.Raw, &raw); err != nil {
		return nil, false
	}
	seen := map[string]bool{}
	tags := make([]string, 0, len(raw))
	for _, t := range raw {
		t = strings.TrimSpace(t)
		if t == "" || seen[t] {
			continue
		}
		seen[t] = true
		tags = append(tags, t)
	}
	return tags, true
}

// setTags replaces the tag list of an article inside the given transaction.
func setTags(tx *gorm.DB, articleID uint, names []string) error {
	if err := tx.Where("article_id = ?", articleID).Delete(&ArticleTag{}).Error; err != nil {
		return err
	}
	for i, name := range names {
		tag := Tag{Name: name}
		if err := tx.Where(Tag{Name: name}).FirstOrCreate(&tag).Error; err != nil {
			return err
		}
		if err := tx.Create(&ArticleTag{ArticleID: articleID, TagID: tag.ID, Position: i}).Error; err != nil {
			return err
		}
	}
	return nil
}

func (a *App) createArticle(c *gin.Context) {
	props, ok := readEnvelope(c, "article")
	if !ok {
		return
	}
	errs := map[string][]string{}
	values := map[string]string{}
	for _, k := range []string{"title", "description", "body"} {
		s, valid := props[k].str()
		switch {
		case !valid:
			errs[k] = []string{"must be a string"}
		case strings.TrimSpace(s) == "":
			errs[k] = []string{"can't be blank"}
		}
		values[k] = s
	}
	tags := []string{}
	if f := props["tagList"]; f.Present && !f.Null {
		var valid bool
		if tags, valid = parseTagList(f); !valid {
			errs["tagList"] = []string{"must be an array of strings"}
		}
	}
	if len(errs) > 0 {
		errorsJSON(c, http.StatusUnprocessableEntity, errs)
		return
	}

	now := time.Now().UTC()
	art := Article{
		Title: values["title"], Description: values["description"], Body: values["body"],
		AuthorID: current(c).ID, CreatedAt: now, UpdatedAt: now,
	}
	err := a.DB.Transaction(func(tx *gorm.DB) error {
		slug, err := a.uniqueSlug(tx, art.Title, 0)
		if err != nil {
			return err
		}
		art.Slug = slug
		if err := tx.Create(&art).Error; err != nil {
			return err
		}
		return setTags(tx, art.ID, tags)
	})
	if err != nil {
		internalError(c, err)
		return
	}
	a.respondArticle(c, http.StatusCreated, &art)
}

func (a *App) updateArticle(c *gin.Context) {
	art, ok := a.findArticle(c)
	if !ok {
		return
	}
	if art.AuthorID != current(c).ID {
		errorJSON(c, http.StatusForbidden, "article", "forbidden")
		return
	}
	props, ok := readEnvelope(c, "article")
	if !ok {
		return
	}
	errs := map[string][]string{}
	targets := map[string]*string{"title": &art.Title, "description": &art.Description, "body": &art.Body}
	titleChanged := false
	for _, k := range []string{"title", "description", "body"} {
		f := props[k]
		if !f.Present {
			continue
		}
		s, valid := f.str()
		switch {
		case !valid:
			errs[k] = []string{"must be a string"}
		case f.Null || strings.TrimSpace(s) == "":
			errs[k] = []string{"can't be blank"}
		default:
			if k == "title" && s != art.Title {
				titleChanged = true
			}
			*targets[k] = s
		}
	}
	var tags []string
	f := props["tagList"]
	if f.Present {
		var valid bool
		if f.Null {
			errs["tagList"] = []string{"can't be null"}
		} else if tags, valid = parseTagList(f); !valid {
			errs["tagList"] = []string{"must be an array of strings"}
		}
	}
	if len(errs) > 0 {
		errorsJSON(c, http.StatusUnprocessableEntity, errs)
		return
	}

	art.UpdatedAt = time.Now().UTC()
	err := a.DB.Transaction(func(tx *gorm.DB) error {
		if titleChanged {
			slug, err := a.uniqueSlug(tx, art.Title, art.ID)
			if err != nil {
				return err
			}
			art.Slug = slug
		}
		if err := tx.Select("Slug", "Title", "Description", "Body", "UpdatedAt").Save(art).Error; err != nil {
			return err
		}
		if f.Present {
			return setTags(tx, art.ID, tags)
		}
		return nil
	})
	if err != nil {
		internalError(c, err)
		return
	}
	a.respondArticle(c, http.StatusOK, art)
}

func (a *App) deleteArticle(c *gin.Context) {
	art, ok := a.findArticle(c)
	if !ok {
		return
	}
	if art.AuthorID != current(c).ID {
		errorJSON(c, http.StatusForbidden, "article", "forbidden")
		return
	}
	err := a.DB.Transaction(func(tx *gorm.DB) error {
		for _, model := range []any{&ArticleTag{}, &Favorite{}, &Comment{}} {
			if err := tx.Where("article_id = ?", art.ID).Delete(model).Error; err != nil {
				return err
			}
		}
		return tx.Delete(&Article{}, art.ID).Error
	})
	if err != nil {
		internalError(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

func (a *App) favorite(c *gin.Context) {
	art, ok := a.findArticle(c)
	if !ok {
		return
	}
	fav := Favorite{UserID: current(c).ID, ArticleID: art.ID}
	if err := a.DB.Where(&fav).FirstOrCreate(&fav).Error; err != nil {
		internalError(c, err)
		return
	}
	a.respondArticle(c, http.StatusOK, art)
}

func (a *App) unfavorite(c *gin.Context) {
	art, ok := a.findArticle(c)
	if !ok {
		return
	}
	if err := a.DB.Where("user_id = ? AND article_id = ?", current(c).ID, art.ID).Delete(&Favorite{}).Error; err != nil {
		internalError(c, err)
		return
	}
	a.respondArticle(c, http.StatusOK, art)
}

func (a *App) listTags(c *gin.Context) {
	tags := []string{}
	err := a.DB.Table("tags").Select("tags.name").
		Joins("JOIN article_tags ON article_tags.tag_id = tags.id").
		Group("tags.id").Order("COUNT(*) DESC, tags.name").Pluck("tags.name", &tags).Error
	if err != nil {
		internalError(c, err)
		return
	}
	c.JSON(http.StatusOK, gin.H{"tags": tags})
}
