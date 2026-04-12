import { MapLibreMapManager } from '@/lib/managers/MapLibreMapManager';
import {
  getFullScreenCancelMethod,
  getFullScreenChangeEvent,
  getFullscreenElement,
  getFullScreenRequestMethod,
} from '@/lib/utils/fullscreen';
import { MapConfig, MapManager } from '@/types/utiles';
import { createContext, Ref, useCallback, useEffect, useRef, useState, type FC, type PropsWithChildren } from 'react';
import { useMapAssets } from './MapAssetContext';
import { getProjectMapDefaults } from '@/lib/settingsApi';
import { useProject } from './ProjectContext';

//AppProvider on old project
interface MapProjectContextType {
  isFullscreen?: boolean;
  onControlClick: (id: string) => void;
  mapElement: Ref<HTMLDivElement>;
  onLayoutReady: () => void;
  mapManager?: MapManager<any>;
}

const MapProjectContext = createContext<MapProjectContextType | undefined>(undefined);

export const MapProjectProvider: FC<PropsWithChildren<{}>> = ({ children }) => {
  const { currentProject } = useProject();
  const [mapConfig, setMapConfig] = useState<MapConfig>();

  const { loadAssets } = useMapAssets();

  const appContainerElement = useRef<HTMLDivElement>(null);
  const mapElement = useRef<HTMLDivElement>(null);

  const [mapManager, setMapManager] = useState<MapManager<any>>();

  const isEventListenerConnected = useRef<boolean>(false);
  const [isFullscreen, setIsFullscreen] = useState<boolean>(false);

  const [isLayoutReady, setIsLayoutReady] = useState<boolean>(false);

  const fullScreenChangeHandler = useCallback(() => {
    setIsFullscreen(getFullscreenElement() !== null);
  }, []);

  const toggleFullscreen = useCallback(() => {
    if (isFullscreen) {
      const requestMethod = getFullScreenCancelMethod();
      if (requestMethod) {
        requestMethod.call(document);
      }
    } else {
      if (appContainerElement?.current) {
        const requestMethod = getFullScreenRequestMethod(appContainerElement.current);
        if (requestMethod) {
          requestMethod.call(appContainerElement.current);
        }
      }
    }
  }, [isFullscreen]);

  const onControlClick = useCallback((id: string) => {
    if (id === 'fullscreen') {
      toggleFullscreen();
    }
  }, []);

  const onLayoutReady = useCallback(() => {
    setIsLayoutReady(true);
  }, []);

  useEffect(() => {
    if (!isEventListenerConnected.current) {
      if (appContainerElement?.current) {
        const eventName = getFullScreenChangeEvent(appContainerElement.current);
        if (eventName) {
          appContainerElement.current.addEventListener(eventName, fullScreenChangeHandler);
        }

        isEventListenerConnected.current = true;
      }
    }
  }, [appContainerElement, isEventListenerConnected]);

  useEffect(() => {
    if (mapElement.current && isLayoutReady) {
      loadAssets();
      setMapManager(new MapLibreMapManager(mapElement.current));
    }
  }, [mapElement, isLayoutReady]);

  useEffect(() => {
    if (currentProject) {
      const loadProjectMapDefaults = async () => {
        const defaults = await getProjectMapDefaults(currentProject?.team_id, currentProject?.id);
        setMapConfig(defaults);
      };

      loadProjectMapDefaults();
    }
  }, [currentProject]);

  return (
    <MapProjectContext.Provider value={{ isFullscreen: false, mapElement: mapElement, onControlClick, onLayoutReady }}>
      {children}
    </MapProjectContext.Provider>
  );
};

export const useMapProject = () => {
  const context = MapProjectContext;
  if (context === undefined) {
    throw new Error('useMapProject must be used within a MapProjectProvider');
  }
  return context;
};
