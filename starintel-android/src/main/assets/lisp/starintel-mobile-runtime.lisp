(defpackage :starintel.mobile.runtime
  (:use :cl)
  (:export :handle-native-request :initialize-runtime :register-actor))

(in-package :starintel.mobile.runtime)

(defvar *database* nil)
(defvar *actor-handlers* (make-hash-table :test #'equal))
(defvar *runtime-directory* nil)
(defvar *mobile-config* nil)
(defconstant +max-init-forms+ 128)
(defconstant +max-init-depth+ 32)
(defconstant +max-init-nodes+ 4096)

(defun json-object (&rest entries)
  (cons :obj entries))

(defun json-get (object key &optional default)
  (or (jsown:val-safe object key) default))

(defun json-array-values (value)
  (etypecase value
    (list value)
    (vector (coerce value 'list))))

(defun response-ok (&optional (result (json-object)))
  (jsown:to-json (json-object (cons "ok" :true) (cons "result" result))))

(defun response-error (condition)
  (jsown:to-json
   (json-object
    (cons "ok" :false)
    (cons "error"
          (json-object
           (cons "code" "runtime-error")
           (cons "detail" (subseq (princ-to-string condition)
                                    0
                                    (min 400 (length (princ-to-string condition))))))))))

(defun allowed-init-head-p (symbol)
  (and (symbolp symbol)
       (member (string-upcase (symbol-name symbol))
               '("QUASAR-CONFIG" "DEFINE-FBP-NODE" "DEFINE-EXPERT")
               :test #'string=)))

(defun validate-init-tree (tree &optional (depth 0) (budget (list +max-init-nodes+)))
  (when (> depth +max-init-depth+)
    (error "init.lisp form exceeds the nesting limit."))
  (decf (car budget))
  (when (minusp (car budget))
    (error "init.lisp exceeds the node limit."))
  (cond
    ((consp tree)
     (validate-init-tree (car tree) (1+ depth) budget)
     (validate-init-tree (cdr tree) (1+ depth) budget))
    ((or (null tree) (stringp tree) (numberp tree) (keywordp tree)) t)
    ((symbolp tree)
     (unless (member (string-upcase (symbol-name tree)) '("T" "NIL") :test #'string=)
       (error "Non-keyword symbol ~S is not allowed in init.lisp data." tree)))
    (t (error "Unsupported value ~S in init.lisp." tree)))
  tree)

(defun read-init-forms (pathname)
  (with-open-file (stream pathname :direction :input)
    (let ((*read-eval* nil)
          (*package* (find-package :starintel.mobile.runtime))
          (forms nil))
      (loop for form = (read stream nil stream)
            until (eq form stream)
            do (when (>= (length forms) +max-init-forms+)
                 (error "init.lisp exceeds the form limit."))
               (unless (and (consp form) (allowed-init-head-p (car form)))
                 (error "Unsupported init.lisp form ~S." (and (consp form) (car form))))
               (validate-init-tree (cdr form))
               (push form forms))
      (nreverse forms))))

(defun reload-init ()
  (unless *runtime-directory*
    (error "Mobile runtime directory is not configured."))
  (let ((pathname (merge-pathnames "init.lisp" *runtime-directory*)))
    (unless (probe-file pathname)
      (error "init.lisp is missing."))
    ;; Store validated forms as configuration data. Never call EVAL or LOAD here.
    (setf *mobile-config* (read-init-forms pathname))
    (json-object (cons "loaded" :true)
                 (cons "form_count" (length *mobile-config*)))))

(defun initialize-runtime (runtime-directory)
  (setf *runtime-directory* (uiop:ensure-directory-pathname runtime-directory))
  (reload-init))

(defun operation-ping ()
  (json-object (cons "runtime" "ecl")
               (cons "ready" :true)
               (cons "init_forms" (length *mobile-config*))))

(defun require-database ()
  (unless (and *database* (tek9:db-is-open-p *database*))
    (error "Tek9 is not open."))
  *database*)

(defun operation-open (arguments)
  (let ((path (json-get arguments "path")))
    (unless (and (stringp path) (> (length path) 0))
      (error "A Tek9 path is required."))
    (when *database*
      (tek9:close-database *database*))
    (setf *database*
          (tek9:open-database
           (tek9:new-database "quasar-android"
                              :path (uiop:ensure-directory-pathname path)
                              :max-size (* 2 1024 1024 1024)
                              :max-dbs 96)))
    (json-object (cons "path" path) (cons "open" :true))))

(defun operation-close ()
  (when *database*
    (tek9:close-database *database*))
  (setf *database* nil)
  (json-object (cons "closed" :true)))

(defun operation-document (arguments)
  (let* ((database (require-database))
         (document (tek9:fetch* database (json-get arguments "id"))))
    (json-object (cons "document" (or document :null)))))

(defun search-match-p (query value)
  (or (string= query "*")
      (search (string-downcase query)
              (string-downcase (jsown:to-json value)))))

(defun operation-search (arguments)
  (let* ((database (require-database))
         (query (json-get arguments "query" "*"))
         (limit (min 100 (max 1 (json-get arguments "limit" 40))))
         (rows nil))
    (tek9:map-database
     database
     :map-fn (lambda (id encoded)
               (declare (ignore encoded))
               (when (< (length rows) limit)
                 (let ((document (tek9:fetch* database id)))
                   (when (and document (search-match-p query document))
                     (push document rows))))))
    (json-object (cons "rows" (nreverse rows)))))

(defun document-id (document)
  (or (json-get document "_id")
      (json-get document "id")
      (tek9:make-key-id)))

(defun put-relation (database operation)
  (let* ((from (json-get operation "from"))
         (predicate (json-get operation "predicate"))
         (to (json-get operation "to"))
         (attributes (json-get operation "attributes" (json-object)))
         (edge-id (format nil "~A|~A|~A" from predicate to)))
    (unless (tek9:fetch-node database from)
      (tek9:put-node database (make-instance 'tek9:node :id from)))
    (unless (tek9:fetch-node database to)
      (tek9:put-node database (make-instance 'tek9:node :id to)))
    (tek9:put-edge database
                   (make-instance 'tek9:edge
                                  :id edge-id
                                  :source from
                                  :predicate predicate
                                  :target to))
    (tek9:put* database attributes :id edge-id :database-name "relation/attributes")))

(defun apply-transaction-operation (database operation)
  (let ((type (json-get operation "type")))
    (cond
      ((string= type "put_document")
       (let ((document (json-get operation "document")))
         (tek9:put* database document :id (document-id document))))
      ((string= type "put_relation")
       (put-relation database operation))
      ((string= type "assert_fact")
       (tek9:put* database (json-get operation "fact") :database-name "facts"))
      ((string= type "enqueue_target")
       (tek9:put* database (json-get operation "request") :database-name "outbox"))
      ((string= type "append_event")
       (tek9:put* database (json-get operation "event") :database-name "events"))
      (t (error "Unsupported Tek9 transaction operation ~S." type)))))

(defun operation-transaction (arguments)
  (let* ((database (require-database))
         (operations (json-array-values (json-get arguments "operations"))))
    (when (> (length operations) 512)
      (error "Tek9 transaction exceeds 512 operations."))
    (tek9:get-graph-db database)
    (tek9:with-write-transaction
        (database :database-names '("facts" "outbox" "events" "relation/attributes"))
      (dolist (operation operations)
        (apply-transaction-operation database operation)))
    (json-object (cons "committed" :true) (cons "operation_count" (length operations)))))

(defun register-actor (actor-id entrypoint handler)
  (check-type actor-id string)
  (check-type entrypoint string)
  (check-type handler function)
  (setf (gethash actor-id *actor-handlers*) (cons entrypoint handler))
  actor-id)

(defun operation-actor-dispatch (arguments)
  (let* ((actor-id (json-get arguments "actor_id"))
         (entrypoint (json-get arguments "entrypoint"))
         (registered (gethash actor-id *actor-handlers*)))
    (unless registered
      (error "Actor ~S is not registered in the trusted mobile image." actor-id))
    (unless (string= entrypoint (car registered))
      (error "Actor entrypoint does not match the trusted registry."))
    (funcall (cdr registered)
             (json-get arguments "message")
             (json-get arguments "config" (json-object)))))

(defun dispatch-operation (operation arguments)
  (cond
    ((string= operation "runtime.ping") (operation-ping))
    ((string= operation "runtime.reload-init") (reload-init))
    ((string= operation "tek9.open") (operation-open arguments))
    ((string= operation "tek9.close") (operation-close))
    ((string= operation "tek9.document") (operation-document arguments))
    ((string= operation "tek9.search") (operation-search arguments))
    ((string= operation "tek9.transaction") (operation-transaction arguments))
    ((string= operation "actor.dispatch") (operation-actor-dispatch arguments))
    (t (error "Unknown mobile runtime operation ~S." operation))))

(defun handle-native-request (request-json)
  (handler-case
      (let* ((request (jsown:parse request-json))
             (operation (json-get request "operation"))
             (arguments (json-get request "arguments" (json-object))))
        (unless (and (stringp operation) (<= (length operation) 128))
          (error "Invalid mobile runtime operation."))
        (response-ok (dispatch-operation operation arguments)))
    (error (condition)
      (response-error condition))))
