package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"unicode"

	"github.com/gin-gonic/gin"
	"gorm.io/gorm"
)

func slugify(title string) string {
	var b strings.Builder
	dash := false
	for _, r := range strings.ToLower(title) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
			dash = false
		} else if !dash && b.Len() > 0 {
			b.WriteByte('-')
			dash = true
		}
	}
	s := strings.TrimRight(b.String(), "-")
	if s == "" {
		s = "article"
	}
	return s
}

func randomSuffix() string {
	buf := make([]byte, 4)
	_, _ = rand.Read(buf)
	return hex.EncodeToString(buf)
}

// ---- rendering ----

// renderArticles builds article JSON objects with batched queries.
func (a *App) renderArticles(arts []Article, viewer uint, withBody bool) []gin.H {
	out := make([]gin.H, 0, len(arts))
	if len(arts) == 0 {
		return out
	}
	ids := make([]uint, len(arts))
	authorIDs := make([]uint, 0, len(arts))
	seen := map[uint]bool{}
	for i, ar := range arts {
		ids[i] = ar.ID
		if !seen[ar.AuthorID] {
			seen[ar.AuthorID] = true
			authorIDs = append(authorIDs, ar.AuthorID)
		}
	}

	authors := map[uint]User{}
	var us []User
	a.db.Where("id IN ?", authorIDs).Find(&us)
	for _, u := range us {
		authors[u.ID] = u
	}

	tags := map[uint][]string{}
	var tagRows []struct {
		ArticleID uint
		Name      string
	}
	a.db.Table("article_tags").Select("article_tags.article_id, tags.name").
		Joins("JOIN tags ON tags.id = article_tags.tag_id").
		Where("article_tags.article_id IN ?", ids).Order("article_tags.article_id, article_tags.position").
		Scan(&tagRows)
	for _, r := range tagRows {
		tags[r.ArticleID] = append(tags[r.ArticleID], r.Name)
	}

	counts := map[uint]int64{}
	var cRows []struct {
		ArticleID uint
		N         int64
	}
	a.db.Table("favorites").Select("article_id, COUNT(*) AS n").
		Where("article_id IN ?", ids).Group("article_id").Scan(&cRows)
	for _, r := range cRows {
		counts[r.ArticleID] = r.N
	}

	favorited := map[uint]bool{}
	following := map[uint]bool{}
	if viewer != 0 {
		var favIDs []uint
		a.db.Model(&Favorite{}).Where("user_id = ? AND article_id IN ?", viewer, ids).Pluck("article_id", &favIDs)
		for _, id := range favIDs {
			favorited[id] = true
		}
		var folIDs []uint
		a.db.Model(&Follow{}).Where("follower_id = ? AND followee_id IN ?", viewer, authorIDs).Pluck("followee_id", &folIDs)
		for _, id := range folIDs {
			following[id] = true
		}
	}

	for _, ar := range arts {
		au := authors[ar.AuthorID]
		tl := tags[ar.ID]
		if tl == nil {
			tl = []string{}
		}
		obj := gin.H{
			"slug": ar.Slug, "title": ar.Title, "description": ar.Description,
			"tagList": tl, "createdAt": fmtTime(ar.CreatedAt), "updatedAt": fmtTime(ar.UpdatedAt),
			"favorited": favorited[ar.ID], "favoritesCount": counts[ar.ID],
			"author": gin.H{"username": au.Username, "bio": au.Bio, "image": au.Image, "following": following[au.ID]},
		}
		if withBody {
			obj["body"] = ar.Body
		}
		out = append(out, obj)
	}
	return out
}

func (a *App) singleArticle(c *gin.Context, status int, ar *Article) {
	c.JSON(status, gin.H{"article": a.renderArticles([]Article{*ar}, viewerID(c), true)[0]})
}

func (a *App) findArticle(c *gin.Context) (*Article, bool) {
	var ar Article
	res := a.db.Where("slug = ?", c.Param("slug")).Limit(1).Find(&ar)
	if res.Error != nil || res.RowsAffected == 0 {
		fail(c, http.StatusNotFound, "article", "not found")
		return nil, false
	}
	return &ar, true
}

// ---- tags ----

// parseTags reads a tagList field: an array of strings, deduplicated in order.
func parseTags(raw json.RawMessage) ([]string, bool) {
	var list []string
	if json.Unmarshal(raw, &list) != nil || list == nil {
		return nil, false
	}
	seen := map[string]bool{}
	out := make([]string, 0, len(list))
	for _, t := range list {
		t = strings.TrimSpace(t)
		if t == "" || seen[t] {
			continue
		}
		seen[t] = true
		out = append(out, t)
	}
	return out, true
}

// setTags replaces the tags of an article, preserving the given order.
func setTags(tx *gorm.DB, articleID uint, names []string) error {
	if err := tx.Where("article_id = ?", articleID).Delete(&ArticleTag{}).Error; err != nil {
		return err
	}
	for i, name := range names {
		if err := tx.Exec("INSERT OR IGNORE INTO tags (name) VALUES (?)", name).Error; err != nil {
			return err
		}
		var tag Tag
		if err := tx.Where("name = ?", name).Take(&tag).Error; err != nil {
			return err
		}
		if err := tx.Create(&ArticleTag{ArticleID: articleID, TagID: tag.ID, Position: i}).Error; err != nil {
			return err
		}
	}
	return nil
}

func (a *App) listTags(c *gin.Context) {
	names := []string{}
	a.db.Table("tags").Distinct("tags.name").
		Joins("JOIN article_tags ON article_tags.tag_id = tags.id").Order("tags.name").Pluck("tags.name", &names)
	c.JSON(http.StatusOK, gin.H{"tags": names})
}

// ---- article endpoints ----

func queryInt(c *gin.Context, key string, def, max int) int {
	n, err := strconv.Atoi(c.Query(key))
	if err != nil || n < 0 {
		return def
	}
	if max > 0 && n > max {
		return max
	}
	return n
}

func (a *App) respondList(c *gin.Context, q *gorm.DB) {
	limit, offset := queryInt(c, "limit", 20, 100), queryInt(c, "offset", 0, 0)
	var total int64
	if err := q.Session(&gorm.Session{}).Count(&total).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	var arts []Article
	if err := q.Session(&gorm.Session{}).Order("articles.created_at DESC, articles.id DESC").
		Limit(limit).Offset(offset).Find(&arts).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.JSON(http.StatusOK, gin.H{
		"articles":      a.renderArticles(arts, viewerID(c), false),
		"articlesCount": total,
	})
}

func (a *App) listArticles(c *gin.Context) {
	q := a.db.Model(&Article{})
	if tag := c.Query("tag"); tag != "" {
		q = q.Where("articles.id IN (SELECT article_tags.article_id FROM article_tags JOIN tags ON tags.id = article_tags.tag_id WHERE tags.name = ?)", tag)
	}
	if author := c.Query("author"); author != "" {
		q = q.Where("articles.author_id IN (SELECT id FROM users WHERE username = ?)", author)
	}
	if fav := c.Query("favorited"); fav != "" {
		q = q.Where("articles.id IN (SELECT favorites.article_id FROM favorites JOIN users ON users.id = favorites.user_id WHERE users.username = ?)", fav)
	}
	a.respondList(c, q)
}

func (a *App) feed(c *gin.Context) {
	q := a.db.Model(&Article{}).
		Where("articles.author_id IN (SELECT followee_id FROM follows WHERE follower_id = ?)", viewerID(c))
	a.respondList(c, q)
}

func (a *App) getArticle(c *gin.Context) {
	if ar, ok := a.findArticle(c); ok {
		a.singleArticle(c, http.StatusOK, ar)
	}
}

func (a *App) createArticle(c *gin.Context) {
	f, ok := bindWrapped(c, "article")
	if !ok {
		return
	}
	title, _, _ := strField(f, "title")
	desc, _, _ := strField(f, "description")
	body, _, _ := strField(f, "body")
	errs := fieldErrors{}
	if blank(title) {
		errs.add("title", "can't be blank")
	}
	if blank(desc) {
		errs.add("description", "can't be blank")
	}
	if blank(body) {
		errs.add("body", "can't be blank")
	}
	var tags []string
	if raw, present := f["tagList"]; present {
		t, valid := parseTags(raw)
		if !valid {
			errs.add("tagList", "is invalid")
		}
		tags = t
	}
	if len(errs) > 0 {
		failMany(c, http.StatusUnprocessableEntity, errs)
		return
	}

	ar := Article{Title: title, Description: desc, Body: body, AuthorID: viewerID(c)}
	base := slugify(title)
	var err error
	for attempt := 0; attempt < 5; attempt++ {
		ar.Slug = base
		if attempt > 0 || a.slugTaken(base) {
			ar.Slug = base + "-" + randomSuffix()
		}
		err = a.db.Transaction(func(tx *gorm.DB) error {
			ar.ID = 0
			if err := tx.Create(&ar).Error; err != nil {
				return err
			}
			return setTags(tx, ar.ID, tags)
		})
		if !isUniqueViolation(err) {
			break
		}
	}
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	a.singleArticle(c, http.StatusCreated, &ar)
}

func (a *App) slugTaken(slug string) bool {
	var n int64
	a.db.Model(&Article{}).Where("slug = ?", slug).Count(&n)
	return n > 0
}

func (a *App) updateArticle(c *gin.Context) {
	ar, ok := a.findArticle(c)
	if !ok {
		return
	}
	if ar.AuthorID != viewerID(c) {
		fail(c, http.StatusForbidden, "article", "forbidden")
		return
	}
	f, ok := bindWrapped(c, "article")
	if !ok {
		return
	}
	errs := fieldErrors{}
	for name, dst := range map[string]*string{"title": &ar.Title, "description": &ar.Description, "body": &ar.Body} {
		if v, present, isStr := strField(f, name); present {
			if !isStr || blank(v) {
				errs.add(name, "can't be blank")
			} else {
				*dst = v
			}
		}
	}
	var tags []string
	setTagList := false
	if raw, present := f["tagList"]; present {
		t, valid := parseTags(raw)
		if !valid {
			errs.add("tagList", "is invalid")
		}
		tags, setTagList = t, true
	}
	if len(errs) > 0 {
		failMany(c, http.StatusUnprocessableEntity, errs)
		return
	}
	err := a.db.Transaction(func(tx *gorm.DB) error {
		if err := tx.Save(ar).Error; err != nil {
			return err
		}
		if setTagList {
			return setTags(tx, ar.ID, tags)
		}
		return nil
	})
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	a.singleArticle(c, http.StatusOK, ar)
}

func (a *App) deleteArticle(c *gin.Context) {
	ar, ok := a.findArticle(c)
	if !ok {
		return
	}
	if ar.AuthorID != viewerID(c) {
		fail(c, http.StatusForbidden, "article", "forbidden")
		return
	}
	err := a.db.Transaction(func(tx *gorm.DB) error {
		for _, m := range []any{&ArticleTag{}, &Favorite{}, &Comment{}} {
			if err := tx.Where("article_id = ?", ar.ID).Delete(m).Error; err != nil {
				return err
			}
		}
		return tx.Delete(&Article{}, ar.ID).Error
	})
	if err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	c.Status(http.StatusNoContent)
}

func (a *App) favorite(c *gin.Context) {
	ar, ok := a.findArticle(c)
	if !ok {
		return
	}
	if err := a.db.Exec("INSERT OR IGNORE INTO favorites (user_id, article_id) VALUES (?, ?)", viewerID(c), ar.ID).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	a.singleArticle(c, http.StatusOK, ar)
}

func (a *App) unfavorite(c *gin.Context) {
	ar, ok := a.findArticle(c)
	if !ok {
		return
	}
	if err := a.db.Where("user_id = ? AND article_id = ?", viewerID(c), ar.ID).Delete(&Favorite{}).Error; err != nil {
		fail(c, http.StatusInternalServerError, "server", "error")
		return
	}
	a.singleArticle(c, http.StatusOK, ar)
}
