(defpackage :starintel.mobile.actors
  (:use :cl))

(in-package :starintel.mobile.actors)

(defun json-object (&rest entries)
  (cons :obj entries))

(defun json-get (object key &optional default)
  (or (jsown:val-safe object key) default))

(defun array (&rest values)
  values)

(defun effect-save-document (document)
  (json-object (cons "type" "save_document")
               (cons "document" document)))

(defun normalize-person (message config)
  (declare (ignore config))
  (let* ((document (json-get message "document"))
         (name (json-get document "name")))
    (when (and (stringp name) (> (length name) 0))
      (setf (jsown:val document "name_normalized")
            (string-downcase (string-trim '(#\Space #\Tab #\Newline) name))))
    (json-object
     (cons "summary" "Normalized the local person record")
     (cons "effects" (array (effect-save-document document))))))

(defun index-relation (message config)
  (declare (ignore config))
  (let* ((document (json-get message "document"))
         (data (json-get document "data" (json-object)))
         (from (or (json-get document "source") (json-get data "source")))
         (predicate (or (json-get document "predicate") (json-get data "predicate")))
         (to (or (json-get document "target") (json-get data "target"))))
    (unless (and (stringp from) (stringp predicate) (stringp to))
      (error "Relation requires source, predicate, and target strings."))
    (json-object
     (cons "summary" "Projected the relation into the local Tek9 graph")
     (cons "effects"
           (array
            (json-object (cons "type" "save_relation")
                         (cons "from" from)
                         (cons "predicate" predicate)
                         (cons "to" to)
                         (cons "attributes" document)))))))

(starintel.mobile.runtime:register-actor
 "local.person-normalizer"
 "STARINTEL.MOBILE.ACTORS:NORMALIZE-PERSON"
 #'normalize-person)

(starintel.mobile.runtime:register-actor
 "local.relation-indexer"
 "STARINTEL.MOBILE.ACTORS:INDEX-RELATION"
 #'index-relation)
