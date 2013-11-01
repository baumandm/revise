(ns bitemyapp.revise.core-test
  (:import [flatland.protobuf PersistentProtocolBufferMap])
  (:require [clojure.test :refer :all]
            [clojure.data :refer [diff]]
            [robert.bruce :refer [try-try-again]]
            [flatland.protobuf.core :as pb]
            [bitemyapp.revise.connection :refer :all]
            [bitemyapp.revise.core :refer :all]
            [bitemyapp.revise.protodefs :refer [Query]]
            [bitemyapp.revise.protoengine :refer [compile-term]]
            [bitemyapp.revise.query :as r]
            [bitemyapp.revise.response :refer [inflate]]))

(defn send-random-delay
  [^PersistentProtocolBufferMap term]
  (if-let [current @current-connection]
    (let [type :START
          token (inc (:token current))
          {:keys [in out]} current]
      (send-protobuf out (pb/protobuf Query {:query term
                                             :token token
                                             :type type}))
      (Thread/sleep (rand-int 100))
      (swap! current-connection update-in [:token] inc)
      (let [r (fetch-response in)]
        (inflate r)))))

(def drop-authors (-> (r/db "test") (r/table-drop-db "authors")))
(def create-authors (-> (r/db "test") (r/table-create-db "authors")))

(def authors [{:name "William Adama" :tv-show "Battlestar Galactica"
               :posts [{:title "Decommissioning speech",
                        :rating 3.5
                        :content "The Cylon War is long over..."},
                       {:title "We are at war",
                        :content "Moments ago, this ship received word..."},
                       {:title "The new Earth",
                        :content "The discoveries of the past few days..."}]}

              {:name "Laura Roslin", :tv-show "Battlestar Galactica",
               :posts [{:title "The oath of office",
                        :rating 4
                        :content "I, Laura Roslin, ..."},
                       {:title "They look like us",
                        :content "The Cylons have the ability..."}]}

              {:name "Jean-Luc Picard", :tv-show "Star Trek TNG",
               :posts [{:title "Civil rights",
                        :content "There are some words I've known since..."}]}])

(def insert-authors (-> (r/db "test") (r/table-db "authors")
                        (r/insert authors)))

(def filter-william (-> (r/table "authors") (r/filter (r/lambda [row] (r/= (r/get-field row :name) "William Adama")))))

(def dump-response (set authors))

(def william-response (set (filter #(= (:name %) "William Adama") authors)))

(defn pare-down [docs]
  (map #(select-keys % [:name :posts :tv-show]) docs))

(defn prep-result [result]
  (set (pare-down (:response result))))

(defn dump-and-william []
  [(future (prep-result (-> (r/table "authors") (run)))) (future (prep-result (run filter-william)))])

(defn test-match-results []
  (let [[dump william] (dump-and-william)]
    (if (and (= dump-response @dump) (= william-response @william))
      (throw (ex-info "I want racy results." {:type :python-exception :cause :eels}))
      (throw (Exception. (str "RACE CONDITION! non-match for " (diff dump-response @dump) (diff william-response @william)))))))

(defn try-until-race []
  (try-try-again {:sleep nil :tries 10 :catch [clojure.lang.ExceptionInfo]} test-match-results))


(deftest ^:race-condition race-condition
  (let [conn (connect)
        drop (-> (r/db "test") (r/table-drop-db "authors") (run))
        create (-> (r/db "test") (r/table-create-db "authors") (run))
        _ (run insert-authors)]
    (testing "Can produce race condition"
      (with-redefs [;; bitemyapp.revise.core/run run-random-delay
                    bitemyapp.revise.connection/send-term send-random-delay]
        (try-until-race)))
    (testing "But I can get sane results normally"
      (let [[dump william] (map deref (dump-and-william))]
        (are [x y] (= x y)
             dump dump-response
             william william-response)))))

(deftest queries
  (testing "Can query RethinkDB"
    (let [conn (connect)

          ;; When connecting: SUCCESS
          ;; {:token 1, :response (("tv_shows"))}
          drop  (run drop-authors)
          create (run create-authors)
          insert (run insert-authors)
          dump (-> (r/table "authors") (run))
          william (run filter-william)

          posts (-> (r/table "authors") (r/filter (r/lambda [row]
                                                            (r/= 2
                                                                 (r/count (r/get-field row :posts)))))
                    (run))

          cherry-pick (-> (r/table "authors") (r/get "7644aaf2-9928-4231-aa68-4e65e31bf219") (run))

          updated (-> (r/table "authors") (r/update {:type "fictional"}) (run))

          admiral-updated (-> (r/table "authors")
                              (r/filter (r/lambda [row]
                                                  (r/= "William Adama"
                                                       (r/get-field row :name))))
                              (r/update {:rank "Admiral"})
                              (run))

          wat (-> (r/table "authors")
                  (r/filter (r/lambda [row]
                                      (r/= "Jean-Luc Picard"
                                           (r/get-field row :name))))
                  (r/update
                   (r/lambda [row]
                             {:posts
                              (r/append (r/get-field row :posts)
                                        {:title "Shakespeare"
                                         :content "What a piece of work is man.."})}))
                  (run))

          deleted (-> (r/table "authors")
                      (r/filter (r/lambda [row]
                                          (r/< (r/count (r/get-field row :posts))
                                               3)))
                      (r/delete)
                      (run))

          second-dump (-> (r/db "test") (r/table-db "authors") (run))

          closed (close conn)]
      (are [x y] (= x y)
      conn    {}
      drop    {}
      create  {}
      insert  []
      dump    []
      william []
      posts   []
      cherry-pick []
      updated []
      admiral-updated []
      wat []
      deleted []
      second-dump []
      closed {}))))

;;; I'm replacing the authors table because it doens't work with plenty of the
;;; api. I'll be removing duplicates in time.
;;; Order based on the README

;;; -----------------------------------------------------------------------
;;; Manipulating databases
(def create-database (r/db-create "revise-test-db"))
(def drop-database (r/db-drop "revise-test-db"))
(def db-list (r/db-list))
;;; -----------------------------------------------------------------------
;;; Manipulating tables
(def create-table
  (-> (r/db "test") (r/table-create-db "revise-test1")))
(def create-table-optargs
  (-> (r/db "test") (r/table-create-db "revise-users"
                                       :primary-key :name)))
(def drop-table
  (-> (r/db "test") (r/table-drop-db "revise-test1")))

(def create-index
  (-> (r/db "test") (r/table-db "revise-users")
      (r/index-create :email (r/lambda [user] (r/get-field user :email)))))
(def create-multi-index
  (-> (r/db "test") (r/table-db "revise-users")
      (r/index-create :demo
                      (r/lambda [user]
                              [(r/get-field user :age)
                               (r/get-field user :country)]))))
(def list-index
  (-> (r/db "test") (r/table-db "revise-users") (r/index-list)))
(def drop-index
  (-> (r/db "test") (r/table-db "revise-users") (r/index-drop :email)))
;;; -----------------------------------------------------------------------
;;; Writing data
(def users (-> (r/db "test") (r/table-db "revise-users")))
(def data-multi
  [{:name "aa" :age 20 :country "us" :email "aa@ex.com"
    :gender "m" :posts ["a" "aa" "aaa"]}
   {:name "bb" :age 21 :country "us" :email "bb@ex.com"
    :gender "f" :posts ["b" "bb"]}
   {:name "cc" :age 20 :country "mx" :email "cc@ex.com"
    :gender "m" :posts ["c"]}
   {:name "dd" :age 20 :country "ca" :email "dd@ex.com"
    :gender "m" :posts ["dddd"]}
   {:name "ee" :age 21 :country "ca" :email "ee@ex.com"
    :gender "f" :posts ["e" "ee" "e" "eeeee"]}
   {:name "ff" :age 22 :country "fr" :email "ff@ex.com"
    :gender "a" :posts []}])
(def data-single
  {:name "gg" :age 21 :country "in" :email "gg@ex.com"
   :gender "b" :posts []})
(def insert-multi
  (-> users (r/insert data-multi)))
(def insert-single
  (-> users (r/insert data-single)))

(def update-append
  (-> users (r/update {:admin false})))
(def update-lambda
  (-> users (r/update (r/lambda [user]
                                (r/+ 1 (r/get-field user :age))))))

(def replace
  (-> users (r/get "dd")
      {:name "ivnhacks" :age 13 :country "ru" :email "joe@ex.com"
       :admin true}))
(def delete
  (-> users (r/get "ivnhacks")
      (r/delete)))
;;; -----------------------------------------------------------------------
;;; Selecting data
(def reference-db
  (r/db "test"))
(def select-table
  users)
(def get-doc
  (-> users (get "aa")))
(def get-all
  (-> users (r/get-all [20 "us"] :demo)))
(def between
  (-> users (r/between "aa" "dd")))
(def filter
  (-> users (r/filter (r/lambda [user] (r/= 20
                                            (r/get-field user :age))))))
;;; -----------------------------------------------------------------------
;;; Joins
(def create-permissions
  (-> (r/db "test") (r/table-create-db "revise-permissions")))
(def permissions-data
  [{:admin false :permission "read"}
   {:admin false :permission "write"}
   {:bond true :permission "execute"}])
(def permissions
  (-> (r/db "test") (r/table-db "permissions")))
(def add-permissions
  (-> permissions (r/insert permissions-data)))
(def inner-join
  (-> users (r/inner-join permissions
                          (r/lambda [user perm]
                                    (r/= (r/get-field user :admin)
                                         (r/get-field perm :admin))))))
(def outer-join
  (-> users (r/outer-join permissions
                          (r/lambda [user perm]
                                    (r/= (r/get-field user :admin)
                                         (r/get-field perm :admin))))))
(def eq-join
  (-> users (r/eq-join permissions :admin)))
(def zip
  (-> eq-join r/zip))
;;; -----------------------------------------------------------------------
;;; Transformations
(def mapped
  (-> users (r/map (r/lambda [user] (r/get-field user :age)))))
(def with-fields
  (-> users (r/with-fields :email :country)))
(def mapcatted
  (-> users (r/mapcat (r/lambda [user]
                                (r/get-field user :posts)))))
(def ordered
  (-> users (r/order-by :age)))
(def skip
  (-> ordered
      (r/skip 2)))
(def limit
  (-> ordered
      (r/limit 2)))
(def slice
  (-> ordered
      (r/slice 1 3)))
(def nth-item
  (-> ordered
      (r/nth 1)))
(def indexes-of
  (r/indexes-of ["a" "b" "a" "c" "a"] "a"))
(def empty-array
  (r/empty? []))
(def union
  (-> users
      (r/union permissions)))
(def sample
  (-> users
      (r/sample 2)))
;;; -----------------------------------------------------------------------
;;; Aggregation
(def count-posts
  (-> users
      (r/map (r/lambda [user] (r/count (r/get-field user :posts))))
      (r/reduce (r/lambda [acc cnt] (r/+ acc cnt)) 0)))
(def distinct-array
  (r/distinct [1 1 2 2 3 3 4 4]))
(def grouped-map-reduce
  (-> users
      (r/grouped-map-reduce
       (r/lambda [user] (r/get-field user :age))
       (r/lambda [user] (r/pluck user :posts))
       (r/lambda [acc user]
                 (r/+ acc (r/count (r/get-field user :posts)))))))
(def grouped-count
  (-> users
      (r/group-by [:age] :count)))
(def grouped-sum
  (-> users
      (r/group-by [:age] {:sum :age})))
(def grouped-average
  (-> users
      (r/group-by [:age] {:avg :age})))
(def contains
  (-> users
      (r/get "aa")
      (r/get-field :posts)
      (r/contains? "aa")))
;;; -----------------------------------------------------------------------
;;; Document manipulation
(def pluck
  (-> users (r/get "aa") (r/pluck :name :age)))
(def without
  (-> users (r/get "aa") (r/pluck :posts :country :gender :email)))
(def merge
  (-> users
      (r/get "aa")
      (r/merge (-> permissions (r/limit 1)))))
(def append
  (-> users
      (r/get "aa")
      (r/update (r/lambda [user]
                          {:posts
                           (r/append (r/get-field user :posts)
                                     "wheee")}))))
(def prepend
  (-> users
      (r/get "aa")
      (r/update (r/lambda [user]
                          {:posts
                           (r/prepend (r/get-field user :posts)
                                      "aaaah")}))))
(def difference
  (r/difference
   ["a" "aa" "aaa"]
   (-> users
       (r/get "aa")
       (r/get-field :posts))))
(def set-insert
  (r/set-insert [1 1 2] 3))
(def set-union
  (r/set-union [1 2 3] [2 3 4]))
(def set-intersection
  (r/set-insert [1 2 3] [3 4 5]))
(def get-field
  (-> users
      (r/get "aa")
      (r/get-field :name)))
(def has-fields
  (-> users
      (r/get "aa")
      (r/has-fields? :name :email :posts)))
(def insert-at
  (r/insert-at [1 3 4 5] 1 2))
(def splice-at
  (r/splice-at [1 2 6 7] 2 [3 4 5]))
(def delete-at
  (r/delete-at [1 2 3 4 5] 1 3))
(def change-at
  (r/change-at [1 2 5 4 5] 2 3))
(def keys
  (-> users
      (r/get "aa")
      (r/keys)))
;;; -----------------------------------------------------------------------
;;; String manipulation
(def match-string
  (-> (r/filter ["Hello" "Also" "Goodbye"]
                (r/lambda [s]
                          (r/match s #"^A")))))
;;; -----------------------------------------------------------------------
;;; Math and logic
(def math
  (r/mod 7
         (r/+ 1
              (r/* 2
                   (r/div 4 2)))))
;; (def and-test
;;   (r/and true true true))
;; (def or-test
;;   (r/or false false true))
(def =test
  (r/= 1 1))
(def not=test
  (r/not= 1 2))
(def >test
  (r/> 5 2))
(def >=test
  (r/>= 5 5))
(def <test
  (r/< 2 5))
(def <=test
  (r/<= 5 5))
(def notatest
  (r/not false))
;;; -----------------------------------------------------------------------
;;; Dates and times
(def now
  (r/now))
(def time
  (r/time 2005 10 20 3 40 5.502 "-06:00"))
(def epoch-time
  (r/epoch-time time))
(def iso8601
  (-> (r/iso8601 "2005-10-20T03:40:05.502-06:00")))
(def in-timezone
  (r/in-timezone time "-07:00"))
(def timezone
  (r/timezone time))
(def during
  (r/during time (r/time 2005 10 19) (r/time 2005 10 21)))
(def date
  (r/date time))
(def time-of-day
  (r/time-of-day time))
(def ->iso8601
  (r/->iso8601 time))
(def ->epoch-time
  (r/->epoch-time time))
;;; -----------------------------------------------------------------------
;;; Access time fields
(def year
  (r/year time))
(def month
  (r/month time))
(def day
  (r/day time))
(def day-of-week
  (r/day-of-week time))
(def day-of-year
  (r/day-of-year time))
(def hours
  (r/hours time))
(def minutes
  (r/minutes time))
(def seconds
  (r/seconds time))
;;; -----------------------------------------------------------------------
;;; Control structures
(def branch
  (r/branch true
            "tis true!"
            "tis false!"))
(def any
  (r/any false false false true))
(def all
  (r/all true true true true))
(def foreach
  ;; TODO
  )
(def error
  (r/error "Wheeee"))
(def default
  (r/default nil "oooooh"))
(def parse-val
  (r/parse-val [1 false "hello" :goodbye {:a 1}]))
(def js
  (r/js "1 + 1"))
(def coerce-to
  (r/coerce-to {:a 1} :array))
(def type
  (r/type [1 2 3]))
(def info
  (r/info users))
(def json
  (r/json "[1,2,3]"))
