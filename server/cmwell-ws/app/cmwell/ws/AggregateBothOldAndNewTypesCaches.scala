//package cmwell.ws
//
//import javax.inject._
//
//import com.typesafe.scalalogging.LazyLogging
//import controllers.NbgToggler
//import ld.cmw.PassiveFieldTypesCacheTrait
//import logic.CRUDServiceFS
//import wsutil.FieldKey
//
//import scala.concurrent.{ExecutionContext, Future}
//
//class AggregateBothOldAndNewTypesCaches(crudService: CRUDServiceFS,
//                                        tbg: NbgToggler) extends PassiveFieldTypesCacheTrait with LazyLogging {
//
//  def crudServiceFS: CRUDServiceFS = crudService
//  def nbg: Boolean = tbg.get
//
//  lazy val cache = crudService.passiveFieldTypesCache
//
//  override def get(fieldKey: FieldKey, forceUpdateForType: Option[Set[Char]] = None)(implicit ec: ExecutionContext): Future[Set[Char]] = {
//    cache.get(fieldKey, forceUpdateForType)
//  }
//
//  override def update(fieldKey: FieldKey, types: Set[Char])(implicit ec: ExecutionContext): Future[Unit] = {
//    cache.update(fieldKey, types)
//  }
//}