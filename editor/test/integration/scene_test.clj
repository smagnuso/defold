(ns integration.scene-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [dynamo.graph :as g]
            [dynamo.graph.test-support :refer [with-clean-system]]
            [dynamo.types :as t]
            [dynamo.geom :as geom]
            [editor.atlas :as atlas]
            [editor.collection :as collection]
            [editor.core :as core]
            [editor.cubemap :as cubemap]
            [editor.game-object :as game-object]
            [editor.image :as image]
            [editor.platformer :as platformer]
            [editor.project :as project]
            [editor.scene :as scene]
            [editor.sprite :as sprite]
            [editor.switcher :as switcher]
            [editor.workspace :as workspace])
  (:import [dynamo.types Region]
           [java.awt.image BufferedImage]
           [java.io File]
           [javax.vecmath Point3d Matrix4d]))

(def not-nil? (complement nil?))

(def project-path "resources/test_project")

(defn- load-test-workspace [graph]
  (let [workspace (workspace/make-workspace graph project-path)]
    (g/transact
      (concat
        (scene/register-view-types workspace)))
    (let [workspace (g/refresh workspace)]
      (g/transact
        (concat
          (collection/register-resource-types workspace)
          (game-object/register-resource-types workspace)
          (cubemap/register-resource-types workspace)
          (image/register-resource-types workspace)
          (atlas/register-resource-types workspace)
          (platformer/register-resource-types workspace)
          (switcher/register-resource-types workspace)
          (sprite/register-resource-types workspace))))
    (g/refresh workspace)))

(defn- load-test-project
  [workspace proj-graph]
  (let [project (first
                      (g/tx-nodes-added
                        (g/transact
                          (g/make-nodes
                            proj-graph
                            [project [project/Project :workspace workspace]]
                            (g/connect workspace :resource-list project :resources)
                            (g/connect workspace :resource-types project :resource-types)))))
        project (project/load-project project)]
    (g/reset-undo! proj-graph)
    project))

(defn- make-aabb [min max]
  (reduce geom/aabb-incorporate (geom/null-aabb) (map #(Point3d. (double-array (conj % 0))) [min max])))

(defn- world-x [node]
  (let [scene (g/node-value node :scene)
        renderables (scene/scene->renderables scene)]
    (.getElement (:world-transform (first (second (first renderables)))) 0 3)))

(defn- test-go-scene [node]
  (let [component (ffirst (g/sources-of node :child-scenes))]
    (is (= 0.0 (world-x node)))
    (g/transact (g/set-property component :position [10 0 0]))
    (is (= 10.0 (world-x node)))))

(defn- test-coll-scene [node]
  (let [go (ffirst (g/sources-of node :child-scenes))]
    (is (= 0.0 (world-x node)))
    (g/transact (g/set-property go :position [10 0 0]))
    (is (= 10.0 (world-x node)))))

(defn- test-sprite-scene [node]
  (let [scene (g/node-value node :scene)
        aabb (make-aabb [-101 -97] [101 97])]
    (is (= (:aabb scene) aabb))))

(defn- test-cubemap-scene [node]
  (let [scene (g/node-value node :scene)]
    (is (= (:aabb scene) geom/unit-bounding-box))))

(defn- test-platformer-scene [node]
  (let [scene (g/node-value node :scene)
        aabb (make-aabb [0 10] [20 10])]
    (is (= (:aabb scene) aabb))))

(defn- test-switcher-scene [node]
  (let [scene (g/node-value node :scene)
        aabb (make-aabb [-45 -45] [45 45])]
    (is (= (:aabb scene) aabb))))

(defn- test-atlas-scene [node]
  (let [scene (g/node-value node :scene)
        aabb (make-aabb [0 0] [2048 1024])]
    (is (= (:aabb scene) aabb))))

(deftest gen-scene
  (testing "Scene generation"
           (with-clean-system
             (let [tests {"**/atlas_sprite.collection" test-coll-scene
                          "**/atlas_sprite.go" test-go-scene
                          "**/atlas.sprite" test-sprite-scene
                          "**/env.cubemap" test-cubemap-scene
                          "**/level1.platformer" test-platformer-scene
                          "**/level01.switcher" test-switcher-scene
                          "**/switcher.atlas" test-atlas-scene}
                   #_{"**/atlas_sprite.collection" test-coll-scene}
                   #_{"**/atlas_sprite.go" test-go-scene}
                   #_{"**/atlas.sprite" test-sprite-scene}
                   #_{"**/env.cubemap" test-cubemap-scene}
                   #_{"**/level1.platformer" test-platformer-scene}
                   #_{"**/level01.switcher" test-switcher-scene}
                   #_{"**/switcher.atlas" test-atlas-scene}
                   workspace     (load-test-workspace world)
                   project-graph (g/make-graph! :history true :volatility 1)
                   project       (load-test-project workspace project-graph)]
               (doseq [[query test-fn] tests]
                 (let [[resource node] (first (project/find-resources project query))]
                   (test-fn node)))))))
