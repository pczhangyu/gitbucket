package app

import service._
import util.{CollaboratorsAuthenticator, ReferrerAuthenticator, JGitUtil}
import util.Directory._
import jp.sf.amateras.scalatra.forms._

class WikiController extends WikiControllerBase 
  with WikiService with RepositoryService with AccountService with CollaboratorsAuthenticator with ReferrerAuthenticator

trait WikiControllerBase extends ControllerBase {
  self: WikiService with RepositoryService with CollaboratorsAuthenticator with ReferrerAuthenticator =>

  case class WikiPageEditForm(pageName: String, content: String, message: Option[String], currentPageName: String)
  
  val newForm = mapping(
    "pageName"        -> trim(label("Page name"          , text(required, maxlength(40), identifier, unique))),
    "content"         -> trim(label("Content"            , text(required))),
    "message"         -> trim(label("Message"            , optional(text()))),
    "currentPageName" -> trim(label("Current page name"  , text()))
  )(WikiPageEditForm.apply)
  
  val editForm = mapping(
    "pageName"        -> trim(label("Page name"          , text(required, maxlength(40), identifier))),
    "content"         -> trim(label("Content"            , text(required))),
    "message"         -> trim(label("Message"            , optional(text()))),
    "currentPageName" -> trim(label("Current page name"  , text(required)))
  )(WikiPageEditForm.apply)
  
  get("/:owner/:repository/wiki")(referrersOnly {
    val owner      = params("owner")
    val repository = params("repository")

    getRepository(owner, repository, baseUrl).map { repositoryInfo =>
      getWikiPage(owner, repository, "Home").map { page =>
        wiki.html.page("Home", page, repositoryInfo, hasWritePermission(owner, repository, context.loginAccount))
      } getOrElse redirect("/%s/%s/wiki/Home/_edit".format(owner, repository))
    } getOrElse NotFound
  })
  
  get("/:owner/:repository/wiki/:page")(referrersOnly {
    val owner      = params("owner")
    val repository = params("repository")
    val pageName   = params("page")

    getRepository(owner, repository, baseUrl).map { repositoryInfo =>
      getWikiPage(owner, repository, pageName).map { page =>
        wiki.html.page(pageName, page, repositoryInfo, hasWritePermission(owner, repository, context.loginAccount))
      } getOrElse redirect("/%s/%s/wiki/%s/_edit".format(owner, repository, pageName)) // TODO URLEncode
    } getOrElse NotFound
  })
  
  get("/:owner/:repository/wiki/:page/_history")(referrersOnly {
    val owner      = params("owner")
    val repository = params("repository")
    val page       = params("page")

    getRepository(owner, repository, baseUrl).map { repositoryInfo =>
      JGitUtil.withGit(getWikiRepositoryDir(owner, repository)){ git =>
        wiki.html.history(Some(page), JGitUtil.getCommitLog(git, "master", path = page + ".md")._1, repositoryInfo)
      }
    } getOrElse NotFound
  })
  
  get("/:owner/:repository/wiki/:page/_compare/:commitId")(referrersOnly {
    val owner      = params("owner")
    val repository = params("repository")
    val page       = params("page")
    val commitId   = params("commitId").split("\\.\\.\\.")

    getRepository(owner, repository, baseUrl).map { repositoryInfo =>
      JGitUtil.withGit(getWikiRepositoryDir(owner, repository)){ git =>
        wiki.html.compare(Some(page), getWikiDiffs(git, commitId(0), commitId(1)), repositoryInfo)
      }
    } getOrElse NotFound
  })
  
  get("/:owner/:repository/wiki/_compare/:commitId")(referrersOnly {
    val owner      = params("owner")
    val repository = params("repository")
    val commitId   = params("commitId").split("\\.\\.\\.")

    getRepository(owner, repository, baseUrl).map { repositoryInfo =>
      JGitUtil.withGit(getWikiRepositoryDir(owner, repository)){ git =>
        wiki.html.compare(None, getWikiDiffs(git, commitId(0), commitId(1)), repositoryInfo)
      }
    } getOrElse NotFound
  })
  
  get("/:owner/:repository/wiki/:page/_edit")(collaboratorsOnly {
    val owner      = params("owner")
    val repository = params("repository")
    val page       = params("page")

    getRepository(owner, repository, baseUrl).map(
      wiki.html.edit(page, getWikiPage(owner, repository, page), _)) getOrElse NotFound
  })
  
  post("/:owner/:repository/wiki/_edit", editForm)(collaboratorsOnly { form =>
    val owner      = params("owner")
    val repository = params("repository")
    
    saveWikiPage(owner, repository, form.currentPageName, form.pageName, 
        form.content, context.loginAccount.get, form.message.getOrElse(""))
    updateLastActivityDate(owner, repository)

    redirect("%s/%s/wiki/%s".format(owner, repository, form.pageName))
  })
  
  get("/:owner/:repository/wiki/_new")(collaboratorsOnly {
    val owner      = params("owner")
    val repository = params("repository")

    getRepository(owner, repository, baseUrl).map(wiki.html.edit("", None, _)) getOrElse NotFound
  })
  
  post("/:owner/:repository/wiki/_new", newForm)(collaboratorsOnly { form =>
    val owner      = params("owner")
    val repository = params("repository")
    
    saveWikiPage(owner, repository, form.currentPageName, form.pageName, 
        form.content, context.loginAccount.get, form.message.getOrElse(""))

    redirect("%s/%s/wiki/%s".format(owner, repository, form.pageName))
  })
  
  get("/:owner/:repository/wiki/:page/_delete")(collaboratorsOnly {
    val owner      = params("owner")
    val repository = params("repository")
    val page       = params("page")
    
    deleteWikiPage(owner, repository, page, context.loginAccount.get.userName, "Delete %s".format(page))
    updateLastActivityDate(owner, repository)

    redirect("%s/%s/wiki".format(owner, repository))
  })
  
  get("/:owner/:repository/wiki/_pages")(referrersOnly {
    val owner      = params("owner")
    val repository = params("repository")

    getRepository(owner, repository, baseUrl).map {
      wiki.html.pages(getWikiPageList(owner, repository), _, hasWritePermission(owner, repository, context.loginAccount))
    } getOrElse NotFound
  })
  
  get("/:owner/:repository/wiki/_history")(referrersOnly {
    val owner      = params("owner")
    val repository = params("repository")

    getRepository(owner, repository, baseUrl).map { repositoryInfo =>
      JGitUtil.withGit(getWikiRepositoryDir(owner, repository)){ git =>
        wiki.html.history(None, JGitUtil.getCommitLog(git, "master")._1, repositoryInfo)
      }
    } getOrElse NotFound
  })

  get("/:owner/:repository/wiki/_blob/*")(referrersOnly {
    val owner      = params("owner")
    val repository = params("repository")
    val path       = multiParams("splat").head

    getFileContent(owner, repository, path).map { content =>
        contentType = "application/octet-stream"
        content
    } getOrElse NotFound
  })

  private def unique: Constraint = new Constraint(){
    def validate(name: String, value: String): Option[String] =
      getWikiPageList(params("owner"), params("repository")).find(_ == value).map(_ => "Page already exists.")
  }

}