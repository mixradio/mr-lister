(ns lister.web
  (:require [clojure.tools.logging :refer [info warn error]]
            [compojure
             [core :refer [defroutes context GET PUT POST DELETE]]
             [handler :as handler]
             [route :as route]]
            [lister.persistence :as persistence]
            [metrics.ring
             [expose :refer [expose-metrics-as-json]]
             [instrument :refer [instrument]]]
            [radix
             [error :refer [wrap-error-handling error-response]]
             [ignore-trailing-slash :refer [wrap-ignore-trailing-slash]]
             [setup :as setup]
             [reload :refer [wrap-reload]]]
            [ring.middleware
             [format-params :refer [wrap-json-kw-params]]
             [format-response :refer [wrap-json-response]]
             [params :refer [wrap-params]]]))

(def json-content-type "application/json;charset=UTF-8")
(def text-plain-type "text/plain;charset=UTF-8")

(def version
  (setup/version "lister"))

(defn response
  [data content-type & [status]]
  {:status (or status 200)
   :headers {"Content-Type" content-type}
   :body data})

(defn healthcheck
  []
  (let [applications-ok? (future (persistence/applications-table-healthcheck))
        environments-ok? (future (persistence/environments-table-healthcheck))
        all-ok? (and @applications-ok? @environments-ok?)]
    (response {:name "lister"
               :version version
               :success all-ok?
               :dependencies [{:name "dynamo-applications" :success @applications-ok?}
                              {:name "dynamo-environments" :success @environments-ok?}]}
              json-content-type (if all-ok? 200 500))))

(defn- create-application
  "Create a new application from the contents of the given request."
  [application]
  (persistence/create-application application)
  (response application json-content-type 201))

(defn- list-applications
  "Get a list of all the stored applications."
  []
  (-> (persistence/list-applications)
      sort
      ((fn [a] {:applications a}))
      (response json-content-type)))

(defn list-applications-with-details
  "Get a list of all the stored applications along with full details."
  []
  (response {:applications (sort #(compare (:name %1) (:name %2)) (map (fn [a] {:name (:name a) :metadata (dissoc a :name)}) (persistence/list-applications-full)))} json-content-type))

(defn- get-application
  "Returns the application with the given name, or '404' if it doesn't exist."
  [application-name]
  (if-let [application (persistence/get-application application-name)]
    (response {:name (:name application)
               :metadata (dissoc application :name)} json-content-type)
    (error-response (str "Application named: '" application-name "' does not exist.") 404)))

(defn- put-application-metadata-item
  "Updates the given application with the given key and value (from the request body)."
  [application-name key value]
  (if-let [result (persistence/update-application-metadata application-name key value)]
    (response result json-content-type 201)
    (error-response (str "Application named: '" application-name "' does not exist.") 404)))

(defn- get-application-metadata-item
  "Get a piece of metadata for an application. Returns 404 if either the application or the metadata is not found"
  [application-name key]
  (if-let [item (persistence/get-application-metadata-item application-name key)]
    (response item json-content-type)
    (error-response (str "Can't find metadata '" key "' for application '" application-name "'.") 404)))

(defn- delete-application-metadata-item
  "Delete an application metadata-item. Always returns 204 NoContent. Idempotent."
  [application-name key]
  (persistence/delete-application-metadata-item application-name key)
  {:status 204})

(defn- delete-application
  "Deletes an application"
  [application]
  (persistence/delete-application application)
  {:status 204})

(defn- list-environments
  "Get a list of all the stored environments."
  []
  (-> (persistence/list-environments)
      sort
      ((fn [e] {:environments e}))
      (response json-content-type)))

(defn- get-environment
  [environment-name]
  (if-let [environment (persistence/get-environment environment-name)]
    (response {:name (:name environment)
               :metadata (dissoc environment :name)} json-content-type)
    (error-response (str "Environment named: '" environment-name "' does not exist.") 404)))

(defn- create-environment
  "Create a new environment with the supplied name, associated with the supplied account"
  [environment account]
  (if account
    (do
      (persistence/create-environment environment account)
      {:status 201})
    {:status 400
     :body "No 'account' parameter defined."}))

(defn- delete-environment
  "Removes the supplied environment"
  [environment]
  (persistence/delete-environment environment)
  {:status 204})

(defroutes applications-routes

  (GET "/"
       {params :params}
       (let [fullview (== 0 (compare (get params "view") "full"))]
        (if fullview
          (list-applications-with-details)
          (list-applications))))

  (GET "/:application-name"
       [application-name]
       (get-application application-name))

  (PUT "/:application-name"
       [application-name]
       (create-application {:name application-name}))

  (DELETE "/:application-name"
          [application-name]
          (delete-application application-name))

  (GET "/:application-name/:key"
       [application-name key]
       (get-application-metadata-item application-name key))

  (PUT "/:application-name/:key"
       [application-name key value]
       (put-application-metadata-item application-name key value))

  (DELETE "/:application-name/:key"
          [application-name key]
          (delete-application-metadata-item application-name key)))

(defroutes environments-routes

  (GET "/"
       []
       (list-environments))

  (GET "/:environment-name"
       [environment-name]
       (get-environment environment-name))

  (PUT "/:environment-name"
       [environment-name account]
       (create-environment environment-name account))

  (DELETE "/:environment-name"
          [environment-name]
          (delete-environment environment-name)))

(defroutes routes
  (context "/1.x"
           []

           (context "/applications"
                    []
                    applications-routes)

           (context "/environments"
                    []
                    environments-routes))

  (context "/applications"
           []
           applications-routes)

  (context "/environments"
           []
           environments-routes)

  (GET "/ping"
       []
       {:status 200
        :headers {"Content-Type" "text/plain"}
        :body "pong"})

  (GET "/healthcheck"
       []
       (healthcheck))

  (route/not-found (error-response "Resource not found" 404)))

(def app
  (-> routes
      (wrap-reload)
      (instrument)
      (wrap-error-handling)
      (wrap-ignore-trailing-slash)
      (wrap-json-response)
      (wrap-json-kw-params)
      (wrap-params)
      (expose-metrics-as-json)))
