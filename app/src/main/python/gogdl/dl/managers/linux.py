"""
Android-compatible Linux manager (simplified)
"""

import logging
from gogdl.dl.managers.v2 import V2Manager

class LinuxManager(V2Manager):
    """Android-compatible Linux download manager"""
    
    def __init__(self, arguments, unknown_arguments, api_handler, max_workers=2):
        super().__init__(arguments, unknown_arguments, api_handler, max_workers)
        self.logger = logging.getLogger("LinuxManager")
        
    def download(self):
        """Download Linux game (uses similar logic to Windows)"""
        self.logger.info(f"Starting Linux download for game {self.game_id}")
        # For now, use the same V2 logic but with Linux platform
        super().download()
