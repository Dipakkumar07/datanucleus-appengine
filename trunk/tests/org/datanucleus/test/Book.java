package org.datanucleus.test;

import com.google.apphosting.api.datastore.Key;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

@Entity
public class Book {
  @Id
  @GeneratedValue(strategy=GenerationType.IDENTITY)
  private String id;
  private String title;
  private String author;
  private String isbn;

  @Column(name="first_published")
  private int firstPublished;

  public Book(String namedKey) {
    this.id = namedKey;
  }

  public Book() {
    this(null);
  }

  public String getId() {
    return id;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getAuthor() {
    return author;
  }

  public void setAuthor(String author) {
    this.author = author;
  }

  public String getIsbn() {
    return isbn;
  }

  public void setIsbn(String isbn) {
    this.isbn = isbn;
  }

  public int getFirstPublished() {
    return firstPublished;
  }

  public void setFirstPublished(int firstPublished) {
    this.firstPublished = firstPublished;
  }

  public String toString() {
    return "\n\nid: " + id + "\ntitle: " + title + "\nauthor: " + author + "\nisbn: " + isbn;
  }

  public static com.google.apphosting.api.datastore.Entity newBookEntity(String namedKey,
      String author, String isbn, String title) {
    return newBookEntity(namedKey, author, isbn, title, 2000);
  }

  public static com.google.apphosting.api.datastore.Entity newBookEntity(String namedKey,
      String author, String isbn, String title, int firstPublished) {
    return newBookEntity(null, namedKey, author, isbn, title, firstPublished);
  }

  public static com.google.apphosting.api.datastore.Entity newBookEntity(Key parentKey,
      String namedKey, String author, String isbn, String title, int firstPublished) {
    com.google.apphosting.api.datastore.Entity e;
    String kind = Book.class.getSimpleName();
    if (namedKey != null) {
      if (parentKey != null) {
        e = new com.google.apphosting.api.datastore.Entity(kind, namedKey, parentKey);
      } else {
        e = new com.google.apphosting.api.datastore.Entity(kind, namedKey);
      }
    } else {
      if (parentKey != null) {
        e = new com.google.apphosting.api.datastore.Entity(kind, parentKey);
      } else {
        e = new com.google.apphosting.api.datastore.Entity(kind);
      }
    }
    e.setProperty("author", author);
    e.setProperty("isbn", isbn);
    e.setProperty("title", title);
    e.setProperty("first_published", firstPublished);
    return e;
  }

  public static com.google.apphosting.api.datastore.Entity newBookEntity(String author, String isbn,
      String title) {
    return newBookEntity(null, null, author, isbn, title, 2000);
  }

  public static com.google.apphosting.api.datastore.Entity newBookEntity(Key parent,
      String author, String isbn, String title) {
    return newBookEntity(parent, null, author, isbn, title, 2000);
  }
}